package com.hkust.marketplace.services;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import com.hkust.marketplace.utils.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly opt in to tests that create and remove their own temporary MySQL accounts. */
@EnabledIfEnvironmentVariable(named = "MARKETPLACE_DB_INTEGRATION", matches = "true")
class PasswordDatabaseTest {
    private final UserRepository users = new UserRepository();
    private final AuthService service = new AuthService(users);

    @Test
    void registrationLoginAndAtomicProfilePasswordChangesPersist() throws Exception {
        String username = "hash_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            User seller = new User(username, "Hash Test", null, UserRole.SELLER);
            seller.setBio("Preserved bio");
            service.register(seller, "OriginalPassword123!");
            assertTrue(PasswordHasher.isHash(users.findByUsername(username).getPasswordHash()));
            assertNotNull(new AuthService().authenticate(username, "OriginalPassword123!", UserRole.SELLER));
            assertThrows(IllegalArgumentException.class, () -> service.register(
                    new User(username, "Duplicate", null, UserRole.SELLER), "StrongPassword123!"));

            User profile = users.findByUsername(username);
            String originalHash = profile.getPasswordHash();
            profile.setFullName("Updated Name");
            assertTrue(service.updateProfile(profile, ""));
            assertEquals(originalHash, users.findByUsername(username).getPasswordHash());
            assertTrue(service.updateProfile(profile, "ChangedPassword123!"));
            assertNull(service.authenticate(username, "OriginalPassword123!", UserRole.SELLER));
            assertNotNull(service.authenticate(username, "ChangedPassword123!", UserRole.SELLER));
            assertEquals("Preserved bio", users.findByUsername(username).getBio());

            User invalid = users.findByUsername(username);
            invalid.setFullName(null);
            assertThrows(SQLException.class, () -> service.updateProfile(invalid, "FailedPassword123!"));
            assertNotNull(service.authenticate(username, "ChangedPassword123!", UserRole.SELLER));
            assertNull(service.authenticate(username, "FailedPassword123!", UserRole.SELLER));
            assertEquals("Updated Name", users.findByUsername(username).getFullName());
            users.setActive(username, false);
            assertNull(service.authenticate(username, "ChangedPassword123!", UserRole.SELLER));
        } finally {
            deleteFixture(username);
        }
    }

    @Test
    void legacyUpgradeIsCaseSensitiveAndCannotOverwriteAChangedPassword() throws Exception {
        String username = "hash_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            try (Connection connection = DatabaseManager.getConnection();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO users (username,full_name,password_hash,role) VALUES (?, ?, ?, 'SELLER')")) {
                insert.setString(1, username);
                insert.setString(2, "Legacy Hash Test");
                insert.setString(3, "LegacyPassword123!");
                insert.executeUpdate();
            }
            assertFalse(users.replacePasswordHash(username, "legacypassword123!", PasswordHasher.hash("AnotherPassword123!")));
            assertNull(service.authenticate(username, "legacypassword123!", UserRole.SELLER));
            assertEquals("LegacyPassword123!", users.findByUsername(username).getPasswordHash());
            assertNotNull(service.authenticate(username, "LegacyPassword123!", UserRole.SELLER));
            String upgraded = users.findByUsername(username).getPasswordHash();
            assertTrue(PasswordHasher.isHash(upgraded));
            assertFalse(users.replacePasswordHash(username, "LegacyPassword123!", PasswordHasher.hash("AnotherPassword123!")));
            assertNull(service.authenticate(username, upgraded, UserRole.SELLER));
            assertNotNull(new AuthService().authenticate(username, "LegacyPassword123!", UserRole.SELLER));
        } finally {
            deleteFixture(username);
        }
    }

    private void deleteFixture(String username) throws SQLException {
        try (Connection connection = DatabaseManager.getConnection();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM users WHERE username = ?")) {
            delete.setString(1, username);
            delete.executeUpdate();
        }
    }
}
