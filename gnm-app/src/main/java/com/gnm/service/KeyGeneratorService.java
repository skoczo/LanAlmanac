package com.gnm.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@ApplicationScoped
public class KeyGeneratorService {

    private static final Logger LOG = Logger.getLogger(KeyGeneratorService.class);
    private static final String KEYS_DIR = "keys";
    private static final String PRIVATE_KEY_PATH = KEYS_DIR + "/privateKey.pem";
    private static final String PUBLIC_KEY_PATH = KEYS_DIR + "/publicKey.pem";

    public void onStart(@Observes StartupEvent ev) {
        generateKeysIfNeeded();
    }

    public synchronized void generateKeysIfNeeded() {
        File dir = new File(KEYS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File privateKeyFile = new File(PRIVATE_KEY_PATH);
        File publicKeyFile = new File(PUBLIC_KEY_PATH);

        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            LOG.info("JWT signing keys not found. Generating a new 2048-bit RSA keypair...");
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair kp = kpg.generateKeyPair();

                // Format Private Key as PKCS#8 PEM
                String privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                        Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPrivate().getEncoded()) +
                        "\n-----END PRIVATE KEY-----\n";

                // Format Public Key as X.509 PEM
                String publicKeyPem = "-----BEGIN PUBLIC KEY-----\n" +
                        Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(kp.getPublic().getEncoded()) +
                        "\n-----END PUBLIC KEY-----\n";

                try (FileWriter privateWriter = new FileWriter(privateKeyFile);
                     FileWriter publicWriter = new FileWriter(publicKeyFile)) {
                    privateWriter.write(privateKeyPem);
                    publicWriter.write(publicKeyPem);
                }

                LOG.info("JWT signing keys generated successfully at " + KEYS_DIR + "/");
            } catch (NoSuchAlgorithmException | IOException e) {
                LOG.error("Failed to generate JWT signing keys", e);
                throw new RuntimeException("Could not initialize JWT keys", e);
            }
        } else {
            LOG.info("Existing JWT signing keys found at " + KEYS_DIR + "/");
        }
    }
}
