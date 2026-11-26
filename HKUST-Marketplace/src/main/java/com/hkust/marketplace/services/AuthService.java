package com.hkust.marketplace.services;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;


/**
 * Authentication and account-management service backed by the MySQL {@code users} table.
 *
 * <p>Handles login with role and active-account checks, Argon2id password hashing plus
 * legacy-hash upgrade, registration, profile updates, and permanent buyer/seller deletion
 * through {@link UserRepository}. The stored account is re-checked before sensitive actions.
 */
public class AuthService {
    /**
     * Classified outcomes returned by {@link AuthService#login(String, String, UserRole)}.
     */
    public enum LoginStatus {
        SUCCESS, INVALID_INPUT, USER_NOT_FOUND, WRONG_ROLE, ACCOUNT_DEACTIVATED,
        INCORRECT_PASSWORD, ACCOUNT_CHANGED
    }

    /**
     * Outcome of a login attempt.
     *
     * @param status the classified result; only {@link LoginStatus#SUCCESS} carries a user
     * @param user the authenticated account on success, otherwise {@code null}
     */
    public record LoginResult(LoginStatus status, User user) { }

    private final UserRepository users;

    /** Creates a service backed by the default {@link UserRepository}. */
    public AuthService() {
        this(new UserRepository());
    }

    /**
     * Creates a service that reads and writes accounts through the supplied repository.
     *
     * @param users the account repository; must not be {@code null}
     * @throws NullPointerException if {@code users} is {@code null}
     */
    public AuthService(UserRepository users) {
        this.users = Objects.requireNonNull(users);
    }

    /**
     * Authenticates a user and returns the resolved account.
     *
     * <p>This is a convenience wrapper around {@link #login(String, String, UserRole)}
     * that discards the classified failure reason. Callers that need to distinguish
     * unknown users, wrong roles, deactivated accounts, and bad passwords should call
     * {@code login} directly.
     *
     * @param username the submitted username; surrounding whitespace is ignored and the
     *     value is matched case-insensitively
     * @param password the submitted plaintext password
     * @param role the portal role required for the account
     * @return the authenticated account, or {@code null} when authentication fails for any
     *     reason
     * @throws SQLException if the account lookup or a legacy-password upgrade fails
     */
    public User authenticate(String username, String password, UserRole role) throws SQLException {
        return login(username, password, role).user();
    }

    /**
     * Authenticates an account once and classifies the outcome.
     *
     * <p>Inputs are rejected before any database work when the username is blank, the
     * password is empty or longer than {@link PasswordHasher#MAX_PASSWORD_LENGTH}, or the
     * role is {@code null}. A stored Argon2id hash is verified with
     * {@link PasswordHasher#verify(String, String)}. A legacy plaintext value is compared
     * exactly and, on success, transparently re-hashed and replaced with a compare-and-set
     * update; if another writer changes the stored password concurrently, the attempt
     * fails with {@link LoginStatus#ACCOUNT_CHANGED} instead of overwriting it.
     *
     * @param username the submitted username; surrounding whitespace is ignored and the
     *     value is matched case-insensitively
     * @param password the submitted plaintext password
     * @param role the portal role required for the account
     * @return the classified outcome; only a {@link LoginStatus#SUCCESS} result carries a
     *     user
     * @throws SQLException if the account lookup or a legacy-password upgrade fails
     */
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
    /**
     * Permanently deletes a buyer or seller account on behalf of an administrator.
     *
     * <p>The caller's account is re-read from the database and must have the
     * {@link UserRole#ADMIN} role. The account's active flag is not re-checked on this
     * path. The target is validated by {@link UserRepository#deleteUser(String)}, which
     * also refuses admin accounts and performs the whole deletion in one transaction
     * (seller listings removed, open deals cancelled, notifications deleted).
     *
     * @param admin the administrator performing the deletion; its stored role is checked
     * @param username the buyer or seller account to delete
     * @throws IllegalArgumentException if {@code admin} is missing or not an admin, or if
     *     the target username is missing, unknown, or an admin account
     * @throws SQLException if the deletion transaction fails; all changes are rolled back
     */
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

    /**
     * Registers a new account after validating and hashing its password.
     *
     * <p>The username is trimmed and lower-cased before it is stored, and the password is
     * hashed with Argon2id. All other fields are persisted as supplied.
     *
     * @param user the account to create; its username, role, and remaining fields must be
     *     populated
     * @param password the new plaintext password
     * @throws IllegalArgumentException if the password does not satisfy
     *     {@link #requireStrongPassword(String)}, or if the repository rejects a duplicate
     *     username or other invalid account data
     * @throws NullPointerException if {@code user} or its username is {@code null}
     * @throws SQLException if the insert fails
     */
    public void register(User user, String password) throws SQLException {
        requireStrongPassword(password);
        user.setUsername(user.getUsername().trim().toLowerCase(Locale.ROOT));
        user.setPasswordHash(PasswordHasher.hash(password));
        users.register(user);
    }

    /**
     * Updates profile fields and, when supplied, the account password.
     *
     * <p>The profile fields and the optional password hash are written in one database
     * update. A non-empty new password must satisfy {@link #requireStrongPassword(String)}
     * and is hashed with Argon2id before it is sent to the repository.
     *
     * @param profile the profile values to save; the username identifies the account
     * @param newPassword a new plaintext password, or {@code null}/empty to keep the
     *     current password
     * @return {@code true} when the account row was updated, {@code false} when no account
     *     matched the username or the write changed no values
     * @throws IllegalArgumentException if a new password is supplied but does not meet the
     *     strength rules
     * @throws SQLException if the update fails
     */
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

    /**
     * Upgrades stored legacy plaintext passwords to Argon2id hashes.
     *
     * <p>Idempotent: accounts that already hold an Argon2id hash are skipped, and each
     * successful upgrade preserves the account's current password. The compare-and-set
     * replacement means a concurrent password change is never overwritten by a stale
     * migration.
     *
     * @return the number of accounts upgraded during this run
     * @throws SQLException if the account listing or any upgrade fails
     */
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

    /**
     * Checks a candidate password against the account password rules.
     *
     * @param password the candidate password; may be {@code null}
     * @return {@code "OK"} when the password is acceptable, otherwise a human-readable
     *     reason (empty, shorter than eight characters, longer than
     *     {@link PasswordHasher#MAX_PASSWORD_LENGTH}, or missing an uppercase letter, a
     *     lowercase letter, or a digit)
     */
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

    /**
     * Validates a candidate password and fails when it is not acceptable.
     *
     * @param password the candidate password
     * @throws IllegalArgumentException with the reason produced by
     *     {@link #checkPasswordStrength(String)} when the password is not
     *     {@code "OK"}
     */
    public static void requireStrongPassword(String password) {
        String result = checkPasswordStrength(password);
        if (!"OK".equals(result)) throw new IllegalArgumentException(result);
    }
}
