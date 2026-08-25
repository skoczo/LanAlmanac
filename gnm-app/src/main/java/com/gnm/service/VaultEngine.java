package com.gnm.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class VaultEngine {

    private static final Logger LOG = Logger.getLogger(VaultEngine.class);
    private static final String VAULT_FILE_PATH = "keys/.vault_master";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int SALT_LENGTH = 16;

    private volatile SecretKey unsealedMasterKey = null;

    void onStart(@Observes StartupEvent ev) {
        String envPassword = System.getenv("GNM_VAULT_PASSWORD");
        if (envPassword != null && !envPassword.trim().isEmpty()) {
            if (!isInitialized()) {
                // First-ever start: auto-initialize the vault using the env password so that
                // headless deployments never need a manual init step.
                try {
                    LOG.info("GNM_VAULT_PASSWORD is set and vault is not yet initialized. Auto-initializing vault...");
                    initializeVault(envPassword);
                    LOG.info("Vault automatically initialized and unsealed using GNM_VAULT_PASSWORD.");
                } catch (Exception e) {
                    LOG.error("Failed to auto-initialize vault using GNM_VAULT_PASSWORD.", e);
                }
            } else {
                // Vault already initialized (subsequent restarts): just unseal it.
                if (unsealVault(envPassword)) {
                    LOG.info("Vault automatically unsealed using GNM_VAULT_PASSWORD environment variable.");
                } else {
                    LOG.error("Failed to unseal vault using GNM_VAULT_PASSWORD: password may be incorrect.");
                }
            }
        }
    }

    public boolean isInitialized() {
        return new File(VAULT_FILE_PATH).exists();
    }

    public boolean isUnsealed() {
        return unsealedMasterKey != null;
    }

    public void initializeVault(String passcode) {
        if (isInitialized()) {
            throw new IllegalStateException("Vault is already initialized");
        }
        try {
            // Generate a random 256-bit Master Key
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256);
            SecretKey masterKey = keyGen.generateKey();

            // Generate salt for Argon2
            byte[] salt = new byte[SALT_LENGTH];
            new SecureRandom().nextBytes(salt);

            // Derive KEK
            byte[] kekBytes = deriveKek(passcode, salt);
            SecretKey kek = new SecretKeySpec(kekBytes, "AES");

            // Encrypt Master Key with KEK using AES-GCM
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, kek, spec);
            
            byte[] encryptedMasterKey = cipher.doFinal(masterKey.getEncoded());

            // Save to file: [salt 16][iv 12][encrypted_key]
            ByteBuffer buffer = ByteBuffer.allocate(salt.length + iv.length + encryptedMasterKey.length);
            buffer.put(salt);
            buffer.put(iv);
            buffer.put(encryptedMasterKey);

            File keysDir = new File("keys");
            if (!keysDir.exists()) keysDir.mkdirs();
            Files.write(new File(VAULT_FILE_PATH).toPath(), buffer.array());

            // Store in memory
            this.unsealedMasterKey = masterKey;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize vault", e);
        }
    }

    public boolean unsealVault(String passcode) {
        if (!isInitialized()) {
            throw new IllegalStateException("Vault is not initialized");
        }
        try {
            byte[] fileData = Files.readAllBytes(new File(VAULT_FILE_PATH).toPath());
            if (fileData.length < SALT_LENGTH + GCM_IV_LENGTH) {
                return false;
            }

            ByteBuffer buffer = ByteBuffer.wrap(fileData);
            byte[] salt = new byte[SALT_LENGTH];
            buffer.get(salt);

            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);

            byte[] encryptedMasterKey = new byte[buffer.remaining()];
            buffer.get(encryptedMasterKey);

            byte[] kekBytes = deriveKek(passcode, salt);
            SecretKey kek = new SecretKeySpec(kekBytes, "AES");

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, kek, spec);

            byte[] masterKeyBytes = cipher.doFinal(encryptedMasterKey);
            this.unsealedMasterKey = new SecretKeySpec(masterKeyBytes, "AES");
            return true;
        } catch (javax.crypto.AEADBadTagException e) {
            // Wrong passcode
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed to unseal vault", e);
        }
    }

    public void lockVault() {
        this.unsealedMasterKey = null;
    }

    private byte[] deriveKek(String passcode, byte[] salt) {
        Argon2Parameters.Builder builder = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(65536)
                .withParallelism(4)
                .withSalt(salt);

        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(builder.build());

        byte[] result = new byte[32]; // 256 bits
        gen.generateBytes(passcode.getBytes(java.nio.charset.StandardCharsets.UTF_8), result, 0, result.length);
        return result;
    }
    
    public EncryptedRecord encrypt(byte[] plaintext) {
        if (!isUnsealed()) throw new IllegalStateException("Vault is sealed");
        if (plaintext == null || plaintext.length == 0) return new EncryptedRecord(new byte[0], new byte[0]);
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, unsealedMasterKey, spec);
            
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedRecord(ciphertext, iv);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    public byte[] decrypt(byte[] ciphertext, byte[] iv) {
        if (!isUnsealed()) throw new IllegalStateException("Vault is sealed");
        if (ciphertext == null || ciphertext.length == 0) return new byte[0];
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, unsealedMasterKey, spec);
            
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
    
    public static class EncryptedRecord {
        public final byte[] ciphertext;
        public final byte[] iv;
        public EncryptedRecord(byte[] ciphertext, byte[] iv) {
            this.ciphertext = ciphertext;
            this.iv = iv;
        }
    }
}
