package com.hkust.marketplace.services;

import com.hkust.marketplace.models.User;
import com.hkust.marketplace.models.UserRole;
import com.hkust.marketplace.repositories.UserRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    private final FakeUsers users = new FakeUsers();
    private final AuthService service = new AuthService(users);

    @Test
    void allRolesLoginReportDistinctFailuresWithoutReturningAnAccount() throws Exception {
        for (UserRole role : UserRole.values()) {
            users.user = null;
            assertRejected(AuthService.LoginStatus.USER_NOT_FOUND, "missing", "Password123!", role);
            assertRejected(AuthService.LoginStatus.INVALID_INPUT, "", "Password123!", role);
            users.user = new User("account", "Account", "Password123!",
                    role == UserRole.BUYER ? UserRole.SELLER : UserRole.BUYER);
            assertRejected(AuthService.LoginStatus.WRONG_ROLE, "account", "Password123!", role);
            assertEquals(0, users.replacements);
            users.user.setRole(role);
            users.user.setActive(false);
            assertRejected(AuthService.LoginStatus.ACCOUNT_DEACTIVATED, "account", "Password123!", role);
            users.user.setActive(true);
            assertRejected(AuthService.LoginStatus.INCORRECT_PASSWORD, "account", "wrong", role);
            assertEquals(0, users.replacements);
            users.allowReplace = false;
            assertRejected(AuthService.LoginStatus.ACCOUNT_CHANGED, "account", "Password123!", role);
            users.allowReplace = true;
        }
    }

    @Test
    void allRolesPasswordsPersistThroughRepositoryAndVerifyOnLogin() throws Exception {
        for (UserRole role : UserRole.values()) {
            User account = new User("account", "Account", null, role);
            service.register(account, "OriginalPassword123!");
            String originalHash = account.getPasswordHash();
            AuthService.LoginResult login = service.login(" ACCOUNT ", "OriginalPassword123!", role);
            assertEquals(AuthService.LoginStatus.SUCCESS, login.status());
            assertSame(account, login.user());
            assertRejected(AuthService.LoginStatus.INCORRECT_PASSWORD, "account", originalHash, role);
            assertTrue(service.updateProfile(account, ""));
            assertEquals(originalHash, account.getPasswordHash());
            assertTrue(service.updateProfile(account, "ChangedPassword123!"));
            assertEquals(account.getPasswordHash(), users.profileHash);
            assertTrue(PasswordHasher.verify("ChangedPassword123!", users.profileHash));
            assertRejected(AuthService.LoginStatus.INCORRECT_PASSWORD, "account", "OriginalPassword123!", role);
            assertEquals(AuthService.LoginStatus.SUCCESS,
                    service.login("account", "ChangedPassword123!", role).status());
        }
    }

    private void assertRejected(AuthService.LoginStatus expected, String username,
                                String password, UserRole role) throws SQLException {
        AuthService.LoginResult result = service.login(username, password, role);
        assertEquals(expected, result.status());
        assertNull(result.user());
    }

    @Test
    void everyRoleRejectsOtherPortalsAndUpgradesLegacyPasswordOnlyInItsOwnPortal() throws Exception {
        for (UserRole accountRole : UserRole.values()) {
            users.user = new User("account", "Account", "LegacyPassword123!", accountRole);
            int previousReplacements = users.replacements;
            for (UserRole portal : UserRole.values()) {
                if (portal != accountRole) {
                    assertRejected(AuthService.LoginStatus.WRONG_ROLE, "account", "LegacyPassword123!", portal);
                }
            }
            assertEquals(previousReplacements, users.replacements);
            assertEquals(AuthService.LoginStatus.SUCCESS,
                    service.login("account", "LegacyPassword123!", accountRole).status());
            assertEquals(previousReplacements + 1, users.replacements);
            assertTrue(PasswordHasher.verify("LegacyPassword123!", users.user.getPasswordHash()));
            for (UserRole portal : UserRole.values()) {
                if (portal != accountRole) {
                    assertRejected(AuthService.LoginStatus.WRONG_ROLE, "account", "LegacyPassword123!", portal);
                }
            }
        }
    }

    @Test
    void registrationStoresOnlyHashAndNormalizesUsername() throws Exception {
        User seller = new User(" NewSeller ", "Seller", null, UserRole.SELLER);
        service.register(seller, "StrongPassword123!");
        assertSame(seller, users.user);
        assertEquals("newseller", seller.getUsername());
        assertTrue(PasswordHasher.isHash(seller.getPasswordHash()));
        assertTrue(PasswordHasher.verify("StrongPassword123!", seller.getPasswordHash()));
    }

    @Test
    void weakRegistrationPasswordDoesNotWriteAccount() {
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new User("seller", "Seller", null, UserRole.SELLER), "weak"));
        assertNull(users.user);
    }

    @Test
    void hashedLoginChecksPasswordRoleAndActiveStatus() throws Exception {
        users.user = new User("seller", "Seller", PasswordHasher.hash("StrongPassword123!"), UserRole.SELLER);
        assertSame(users.user, service.authenticate(" SELLER ", "StrongPassword123!", UserRole.SELLER));
        assertNull(service.authenticate("seller", "wrong", UserRole.SELLER));
        assertNull(service.authenticate("seller", users.user.getPasswordHash(), UserRole.SELLER));
        assertNull(service.authenticate("seller", "StrongPassword123!", UserRole.ADMIN));
        users.user.setActive(false);
        assertNull(service.authenticate("seller", "StrongPassword123!", UserRole.SELLER));
        assertEquals(0, users.replacements);
    }

    @Test
    void legacyLoginUpgradesOnlyAfterCorrectPasswordAndAllowedRole() throws Exception {
        users.user = new User("seller", "Seller", "LegacyPass123", UserRole.SELLER);
        assertNull(service.authenticate("seller", "legacypass123", UserRole.SELLER));
        assertNull(service.authenticate("seller", "LegacyPass123", UserRole.ADMIN));
        assertEquals(0, users.replacements);
        assertSame(users.user, service.authenticate("seller", "LegacyPass123", UserRole.SELLER));
        assertEquals(1, users.replacements);
        assertTrue(PasswordHasher.verify("LegacyPass123", users.user.getPasswordHash()));
        assertNotEquals("LegacyPass123", users.user.getPasswordHash());
        assertSame(users.user, service.authenticate("seller", "LegacyPass123", UserRole.SELLER));
        assertEquals(1, users.replacements);
    }

    @Test
    void concurrentPasswordChangeIsNotOverwrittenDuringUpgrade() throws Exception {
        users.user = new User("seller", "Seller", "LegacyPass123", UserRole.SELLER);
        users.allowReplace = false;
        assertNull(service.authenticate("seller", "LegacyPass123", UserRole.SELLER));
        assertEquals("LegacyPass123", users.user.getPasswordHash());
    }

    @Test
    void invalidAndDeactivatedAccountsAreNeverUpgraded() throws Exception {
        assertNull(service.authenticate(null, "password", UserRole.ADMIN));
        assertNull(service.authenticate("missing", "password", UserRole.ADMIN));
        users.user = new User("seller", "Seller", "LegacyPass123", UserRole.SELLER);
        users.user.setActive(false);
        assertNull(service.authenticate("seller", "LegacyPass123", UserRole.SELLER));
        users.user.setActive(true);
        users.user.setPasswordHash("$argon2id$broken");
        assertNull(service.authenticate("seller", "$argon2id$broken", UserRole.SELLER));
        assertEquals(0, users.replacements);
    }

    @Test
    void blankProfilePasswordPreservesPasswordAndNewPasswordIsHashed() throws Exception {
        User profile = new User("seller", "Seller", null, UserRole.SELLER);
        assertTrue(service.updateProfile(profile, ""));
        assertNull(users.profileHash);
        assertTrue(service.updateProfile(profile, "NewStrongPassword123!"));
        assertTrue(PasswordHasher.verify("NewStrongPassword123!", users.profileHash));
        assertEquals(users.profileHash, profile.getPasswordHash());
    }

    @Test
    void failedProfileUpdateDoesNotPublishNewPassword() throws Exception {
        User profile = new User("seller", "Seller", null, UserRole.SELLER);
        assertThrows(IllegalArgumentException.class, () -> service.updateProfile(profile, "weak"));
        assertEquals(0, users.profileUpdates);
        users.allowProfileUpdate = false;
        assertFalse(service.updateProfile(profile, "NewStrongPassword123!"));
        assertNull(profile.getPasswordHash());
    }

    @Test
    void bulkMigrationIsIdempotentAndPreservesPasswords() throws Exception {
        users.user = new User("admin", "Admin", "admin123", UserRole.ADMIN);
        assertEquals(1, service.migrateLegacyPasswords());
        assertTrue(PasswordHasher.verify("admin123", users.user.getPasswordHash()));
        assertEquals(0, service.migrateLegacyPasswords());
    }

    @Test
    void connectionFailureIsPropagatedWithoutAuthenticating() {
        AuthService disconnected = new AuthService(new UserRepository() {
            @Override
            public User findByUsername(String username) throws SQLException {
                throw new SQLException("Connection unavailable");
            }
        });
        assertThrows(SQLException.class, () -> disconnected.authenticate("admin", "admin123", UserRole.ADMIN));
    }

    private static class FakeUsers extends UserRepository {
        User user;
        int replacements;
        int profileUpdates;
        String profileHash;
        boolean allowReplace = true;
        boolean allowProfileUpdate = true;

        @Override public User findByUsername(String username) {
            return user != null && user.getUsername().equals(username) ? user : null;
        }
        @Override public void register(User user) { this.user = user; }
        @Override public boolean replacePasswordHash(String username, String previous, String hash) {
            if (!allowReplace || !previous.equals(user.getPasswordHash())) return false;
            user.setPasswordHash(hash);
            replacements++;
            return true;
        }
        @Override public boolean updateProfile(User profile, String hash) {
            profileUpdates++;
            profileHash = hash;
            return allowProfileUpdate;
        }
        @Override public List<User> findAll() { return user == null ? List.of() : List.of(user); }
    }
}
