package com.hkust.marketplace.services;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Salted Argon2id hashes; the encoded value includes its salt and work parameters. */
public final class PasswordHasher {
    /** Maximum accepted plaintext password length in characters. */
    public static final int MAX_PASSWORD_LENGTH = 1024;
    private static final Argon2Function FUNCTION =
            Argon2Function.getInstance(19 * 1024, 2, 1, 32, Argon2.ID);
    private static final Pattern ENCODED = Pattern.compile(
            "\\$argon2id\\$v=19\\$m=([0-9]{1,5}),t=([0-9]{1,2}),p=([0-9])"
                    + "\\$([A-Za-z0-9+/]{22})\\$([A-Za-z0-9+/]{43})");

    private PasswordHasher() { }

    /**
     * Hashes a password with Argon2id and a fresh random salt.
     *
     * <p>The encoded result includes the algorithm, version, work parameters (19 MiB of
     * memory, two iterations, one lane), the 16-byte salt, and the 32-byte hash.
     *
     * @param password the plaintext password
     * @return the encoded Argon2id hash
     * @throws IllegalArgumentException if the password is {@code null}, empty, or longer
     *     than {@link #MAX_PASSWORD_LENGTH} characters
     */
    public static String hash(String password) {
        if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must contain 1 to 1024 characters.");
        }
        return Password.hash(password).addRandomSalt(16).with(FUNCTION).getResult();
    }

    /**
     * Verifies a plaintext password against a stored Argon2id hash.
     *
     * <p>Returns {@code false} instead of failing for missing or oversized passwords,
     * values that are not a well-formed supported hash, and malformed encoded parameters.
     *
     * @param password the submitted plaintext password
     * @param encoded the stored encoded hash
     * @return {@code true} only when the password matches the encoded hash
     */
    public static boolean verify(String password, String encoded) {
        if (password == null || password.isEmpty() || password.length() > MAX_PASSWORD_LENGTH
                || !isHash(encoded)) {
            return false;
        }
        try {
            return Password.check(password, encoded).with(Argon2Function.getInstanceFromHash(encoded));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Checks whether a stored value is an encoded Argon2id hash with bounded parameters.
     *
     * <p>Accepts the format produced by {@link #hash(String)} (version 19, 16-byte salt,
     * 32-byte hash) and rejects values whose parallelism, memory, or iteration parameters
     * are outside the supported ranges, so untrusted input cannot make the verifier
     * allocate excessive resources.
     *
     * @param encoded the stored value; may be {@code null}
     * @return {@code true} when the value is a supported encoded Argon2id hash
     */
    public static boolean isHash(String encoded) {
        if (encoded == null) return false;
        Matcher matcher = ENCODED.matcher(encoded);
        if (!matcher.matches()) return false;
        int memory = Integer.parseInt(matcher.group(1));
        int iterations = Integer.parseInt(matcher.group(2));
        int parallelism = Integer.parseInt(matcher.group(3));
        // Bound work before allowing the library to allocate memory from stored parameters.
        if (parallelism < 1 || parallelism > 4 || memory < 8 * parallelism
                || memory > 65536 || iterations < 1 || iterations > 10) return false;
        try {
            return Base64.getDecoder().decode(matcher.group(4)).length == 16
                    && Base64.getDecoder().decode(matcher.group(5)).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean isLegacyPassword(String stored) {
        return stored != null && !stored.isEmpty() && !stored.startsWith("$argon2");
    }

    static boolean verifyLegacy(String password, String stored) {
        return password != null && !password.isEmpty() && password.length() <= MAX_PASSWORD_LENGTH
                && isLegacyPassword(stored) && MessageDigest.isEqual(
                password.getBytes(StandardCharsets.UTF_8), stored.getBytes(StandardCharsets.UTF_8));
    }
}
