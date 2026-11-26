package com.hkust.marketplace.services;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    @Test
    void randomSaltsProduceDifferentHashesForTheSamePassword() {
        String first = PasswordHasher.hash("StrongPassword123!");
        String second = PasswordHasher.hash("StrongPassword123!");
        assertNotEquals(first, second);
        assertTrue(first.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertTrue(first.length() <= 255);
        assertTrue(PasswordHasher.verify("StrongPassword123!", first));
        assertTrue(PasswordHasher.verify("StrongPassword123!", second));
        assertFalse(PasswordHasher.verify("wrong", first));
        assertFalse(PasswordHasher.verify(first, first), "The stored hash is not a login password");
    }

    @Test
    void unicodeSpacesAndLongPasswordsAreNotTrimmedOrTruncated() {
        String password = "  Secret123密碼!" + "x".repeat(300) + "  ";
        String hash = PasswordHasher.hash(password);
        assertTrue(PasswordHasher.verify(password, hash));
        assertFalse(PasswordHasher.verify(password.trim(), hash));
        assertFalse(PasswordHasher.verify(password.substring(0, 72), hash));
    }

    @Test
    void malformedOrExcessiveHashParametersFailClosed() {
        String valid = PasswordHasher.hash("StrongPassword123!");
        for (String invalid : new String[] { "", "plaintext", "$argon2id$broken",
                valid.replace("m=19456", "m=99999"), valid.replace("t=2", "t=99"),
                valid.replace("p=1", "p=0"), valid.substring(0, valid.length() - 1) }) {
            assertFalse(PasswordHasher.verify(invalid, invalid));
            assertFalse(PasswordHasher.verify("StrongPassword123!", invalid));
        }
        assertFalse(PasswordHasher.verify(null, valid));
        assertFalse(PasswordHasher.verify("", valid));
        assertFalse(PasswordHasher.verify("StrongPassword123!", null));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash(""));
        assertThrows(IllegalArgumentException.class, () -> PasswordHasher.hash("x".repeat(1025)));
    }

    @Test
    void malformedArgon2ValuesCannotFallBackToPlaintextAuthentication() {
        assertFalse(PasswordHasher.verifyLegacy("$argon2id$broken", "$argon2id$broken"));
        assertTrue(PasswordHasher.verifyLegacy("$Money123!", "$Money123!"));
        assertFalse(PasswordHasher.verifyLegacy("legacypass123", "LegacyPass123"));
    }

    @Test
    void freshDatabaseSeedsOnlyTheExistingAdminAndDemoUsersAreTestFixtures() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        String fixtures = Files.readString(Path.of("src/test/resources/db/buyer-seller-fixtures.sql"));
        Pattern seedAccount = Pattern.compile("\\('([^']+)',\\s*'[^']*',\\s*'([^']*)',\\s*'(ADMIN|BUYER|SELLER)'");
        Matcher admin = seedAccount.matcher(schema);
        assertTrue(admin.find());
        assertEquals("admin", admin.group(1));
        assertEquals("ADMIN", admin.group(3));
        assertTrue(PasswordHasher.isHash(admin.group(2)));
        assertEquals("OK", AuthService.checkPasswordStrength("Admin123"));
        assertTrue(PasswordHasher.verify("Admin123", admin.group(2)));
        Set<String> hashes = new HashSet<>();
        hashes.add(admin.group(2));
        assertFalse(admin.find(), "The application schema must seed only the admin account");

        Matcher accounts = seedAccount.matcher(fixtures);
        int count = 0;
        while (accounts.find()) {
            String username = accounts.group(1);
            String hash = accounts.group(2);
            assertTrue(PasswordHasher.isHash(hash), username);
            assertNotEquals("ADMIN", accounts.group(3));
            assertTrue(PasswordHasher.verify("password1", hash), username);
            hashes.add(hash);
            count++;
        }
        assertEquals(4, count);
        assertEquals(5, hashes.size());
    }
}
