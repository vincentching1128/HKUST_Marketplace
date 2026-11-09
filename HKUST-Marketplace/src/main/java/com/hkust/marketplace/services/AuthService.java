package com.hkust.marketplace.services;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;


public class AuthService {
    private final UserRepository users;

    public AuthService() {
        this(new UserRepository());
    }

    public AuthService(UserRepository users) {
        this.users = Objects.requireNonNull(users);
    }

    public User authenticate(String username, String password, UserRole role) throws SQLException {
        if (username == null || username.isBlank() || password == null || password.isEmpty()
                || password.length() > PasswordHasher.MAX_PASSWORD_LENGTH || role == null) return null;
        User user = users.findByUsername(username.trim().toLowerCase(Locale.ROOT));
        if (user == null || user.getRole() != role || !user.isActive()) return null;
        String stored = user.getPasswordHash();
        if (PasswordHasher.isHash(stored)) {
            return PasswordHasher.verify(password, stored) ? user : null;
        }
        if (!PasswordHasher.verifyLegacy(password, stored)) return null;

        String upgraded = PasswordHasher.hash(password);
        // A simultaneous password change must not be overwritten by this login.
        if (!users.replacePasswordHash(user.getUsername(), stored, upgraded)) return null;
        user.setPasswordHash(upgraded);
        return user;
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
