package com.hkust.marketplace.services;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;


public class AuthService {
    public enum LoginStatus {
        SUCCESS, INVALID_INPUT, USER_NOT_FOUND, WRONG_ROLE, ACCOUNT_DEACTIVATED,
        INCORRECT_PASSWORD, ACCOUNT_CHANGED
    }

    public record LoginResult(LoginStatus status, User user) { }

    private final UserRepository users;

    public AuthService() {
        this(new UserRepository());
    }

    public AuthService(UserRepository users) {
        this.users = Objects.requireNonNull(users);
    }

    public User authenticate(String username, String password, UserRole role) throws SQLException {
        return login(username, password, role).user();
    }

    /** Uses the repository once to identify the account and explain a rejected login. */
    public LoginResult login(String username, String password, UserRole role) throws SQLException {
        if (username == null || username.isBlank() || password == null || password.isEmpty()
                || password.length() > PasswordHasher.MAX_PASSWORD_LENGTH || role == null)
            return new LoginResult(LoginStatus.INVALID_INPUT, null);
        User user = users.findByUsername(username.trim().toLowerCase(Locale.ROOT));
        if (user == null) return new LoginResult(LoginStatus.USER_NOT_FOUND, null);
        if (user.getRole() != role) return new LoginResult(LoginStatus.WRONG_ROLE, null);
        if (!user.isActive()) return new LoginResult(LoginStatus.ACCOUNT_DEACTIVATED, null);
        String stored = user.getPasswordHash();
        if (PasswordHasher.isHash(stored)) {
            return PasswordHasher.verify(password, stored)
                    ? new LoginResult(LoginStatus.SUCCESS, user)
                    : new LoginResult(LoginStatus.INCORRECT_PASSWORD, null);
        }
        if (!PasswordHasher.verifyLegacy(password, stored))
            return new LoginResult(LoginStatus.INCORRECT_PASSWORD, null);

        String upgraded = PasswordHasher.hash(password);
        // A simultaneous password change must not be overwritten by this login.
        if (!users.replacePasswordHash(user.getUsername(), stored, upgraded))
            return new LoginResult(LoginStatus.ACCOUNT_CHANGED, null);
        user.setPasswordHash(upgraded);
        return new LoginResult(LoginStatus.SUCCESS, user);
    }

    //check the stored caller role before allowing permanent account deletion
    public void deleteUserAsAdmin(User admin, String username) throws SQLException {
        if (admin == null || admin.getUsername() == null || admin.getUsername().isBlank()) {
            throw new IllegalArgumentException("An admin account is required to delete users.");
        }
        User current = users.findByUsername(admin.getUsername());
        if (current == null || current.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("An admin account is required to delete users.");
        }
        //the repository validates the target and refuses to delete admin accounts
        users.deleteUser(username);
    }

    public void register(User user, String password) throws SQLException {
        requireStrongPassword(password);
        user.setUsername(user.getUsername().trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(PasswordHasher.hash(password));
        users.register(user);
    }

    /** Save the profile and optional new password in one database update. */
    public boolean updateProfile(User profile, String newPassword) throws SQLException {
        String hash = null;
        if (newPassword != null && !newPassword.isEmpty()) {
            requireStrongPassword(newPassword);
            hash = PasswordHasher.hash(newPassword);
        }
        if (!users.updateProfile(profile, hash)) return false;
        if (hash != null) profile.setPasswordHash(hash);
        return true;
    }

    /** Idempotent upgrade of existing accounts, preserving their current passwords. */
    public int migrateLegacyPasswords() throws SQLException {
        int migrated = 0;
        for (User user : users.findAll()) {
            String stored = user.getPasswordHash();
            if (PasswordHasher.isLegacyPassword(stored)
                    && users.replacePasswordHash(user.getUsername(), stored, PasswordHasher.hash(stored))) {
                migrated++;
            }
        }
        return migrated;
    }

    public static String checkPasswordStrength(String password) {
        if (password == null || password.isEmpty()) return "Password cannot be empty.";
        if (password.length() < 8) return "Password must be at least 8 characters.";
        if (password.length() > PasswordHasher.MAX_PASSWORD_LENGTH) return "Password must be at most 1024 characters.";
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        return hasUpper && hasLower && hasDigit ? "OK"
                : "Password should contain uppercase, lowercase and a digit.";
    }

    public static void requireStrongPassword(String password) {
        String result = checkPasswordStrength(password);
        if (!"OK".equals(result)) throw new IllegalArgumentException(result);
    }
}
