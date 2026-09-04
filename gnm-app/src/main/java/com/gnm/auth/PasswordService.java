package com.gnm.auth;

import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Password hashing and verification using Argon2id via Bouncy Castle.
 * Produces self-contained hash strings: $argon2id$v=19$m=65536,t=3,p=1$salt$hash
 */
@ApplicationScoped
public class PasswordService {

    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;
    private static final int MEMORY_KB = 65536;  // 64 MB
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 1;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Hash a plaintext password with a random salt using Argon2id.
     * Returns a self-describing hash string.
     */
    public String hashPassword(String plaintext) {
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        byte[] hash = computeArgon2(plaintext.toCharArray(), salt);

        String saltB64 = Base64.getEncoder().withoutPadding().encodeToString(salt);
        String hashB64 = Base64.getEncoder().withoutPadding().encodeToString(hash);

        return String.format("$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
                MEMORY_KB, ITERATIONS, PARALLELISM, saltB64, hashB64);
    }

    /**
     * Verify a plaintext password against a stored Argon2id hash string.
     */
    public boolean verifyPassword(String plaintext, String storedHash) {
        if (storedHash == null || !storedHash.startsWith("$argon2id$")) {
            return false;
        }

        try {
            // Parse: $argon2id$v=19$m=65536,t=3,p=1$salt$hash
            String[] parts = storedHash.split("\\$");
            if (parts.length != 6) {
                return false;
            }

            String params = parts[3]; // m=65536,t=3,p=1
            String saltB64 = parts[4];
            String expectedHashB64 = parts[5];

            int memory = 0, iterations = 0, parallelism = 0;
            for (String param : params.split(",")) {
                String[] kv = param.split("=");
                switch (kv[0]) {
                    case "m" -> memory = Integer.parseInt(kv[1]);
                    case "t" -> iterations = Integer.parseInt(kv[1]);
                    case "p" -> parallelism = Integer.parseInt(kv[1]);
                }
            }

            byte[] salt = Base64.getDecoder().decode(saltB64);
            byte[] expectedHash = Base64.getDecoder().decode(expectedHashB64);

            byte[] computedHash = computeArgon2(plaintext.toCharArray(), salt, memory, iterations, parallelism);

            return constantTimeEquals(expectedHash, computedHash);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] computeArgon2(char[] password, byte[] salt) {
        return computeArgon2(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM);
    }

    private byte[] computeArgon2(char[] password, byte[] salt, int memory, int iterations, int parallelism) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memory)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] hash = new byte[HASH_LENGTH];
        generator.generateBytes(password, hash);
        return hash;
    }

    /**
     * Constant-time comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
