package com.gnm.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList; 
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.HashMap;

import com.gnm.dto.backup.*;
import com.gnm.model.*;

@ApplicationScoped
public class BackupService {

    public static final String CURRENT_BACKUP_VERSION = "4";

    @Transactional
    public LanAlmanacBackup exportData(boolean includeSecrets) {
        LanAlmanacBackup backup = new LanAlmanacBackup();
        backup.version = CURRENT_BACKUP_VERSION;
        backup.exportDate = Instant.now();

        List<PhysicalDevice> devices = PhysicalDevice.listAll();
        backup.devices = devices.stream().map(d -> mapToBackup(d, includeSecrets)).collect(Collectors.toList());

        List<NetworkLink> links = NetworkLink.listAll();
        backup.links = links.stream().map(this::mapToBackup).collect(Collectors.toList());

        return backup;
    }

    @Transactional
    public void importData(LanAlmanacBackup backup) {
        // Wipe existing data
        NetworkLink.deleteAll();
        PhysicalDevice.deleteAll(); // Cascade will delete credentials, identities, fingerprints
        PhysicalDevice.getEntityManager().flush();
        
        Map<UUID, PhysicalDevice> deviceMap = new HashMap<>();

        // Insert new data
        if (backup.devices != null) {
            for (PhysicalDeviceBackup db : backup.devices) {
                UUID oldId = db.id;
                db.id = null; // Clear ID so Hibernate treats it as new
                PhysicalDevice device = mapFromBackup(db);
                device.persist();
                deviceMap.put(oldId, device);
            }
        }

        if (backup.links != null) {
            for (NetworkLinkBackup lb : backup.links) {
                lb.id = null;
                NetworkLink link = mapFromBackup(lb);
                if (lb.sourceDeviceId != null && deviceMap.containsKey(lb.sourceDeviceId)) {
                    link.sourceDevice = deviceMap.get(lb.sourceDeviceId);
                } else {
                    link.sourceDevice = null;
                }
                if (lb.targetDeviceId != null && deviceMap.containsKey(lb.targetDeviceId)) {
                    link.targetDevice = deviceMap.get(lb.targetDeviceId);
                } else {
                    link.targetDevice = null;
                }
                link.persist();
            }
        }
    }

    // --- Mapping to Backup DTOs ---

    private PhysicalDeviceBackup mapToBackup(PhysicalDevice device, boolean includeSecrets) {
        PhysicalDeviceBackup dto = new PhysicalDeviceBackup();
        dto.id = device.id;
        dto.displayName = device.displayName;
        dto.deviceType = device.deviceType;
        dto.osFamily = device.osFamily;
        dto.osVersion = device.osVersion;
        dto.manufacturer = device.manufacturer;
        dto.model = device.model;
        dto.locationNote = device.locationNote;
        dto.confidenceScore = device.confidenceScore;
        dto.manuallyVerified = device.manuallyVerified;
        dto.firstSeen = device.firstSeen;
        dto.lastSeen = device.lastSeen;
        dto.status = device.status;
        dto.managementState = device.managementState;
        if (device.labels != null) dto.labels.addAll(device.labels);
        
        if (device.identities != null) {
            dto.identities = device.identities.stream().map(this::mapToBackup).collect(Collectors.toList());
        }
        if (device.fingerprints != null) {
            dto.fingerprints = device.fingerprints.stream().map(this::mapToBackup).collect(Collectors.toList());
        }
        if (device.credentials != null) {
            dto.credentials = device.credentials.stream().map(c -> mapToBackup(c, includeSecrets)).collect(Collectors.toList());
        }
        return dto;
    }

    private NetworkIdentityBackup mapToBackup(NetworkIdentity id) {
        NetworkIdentityBackup dto = new NetworkIdentityBackup();
        dto.id = id.id;
        dto.ipAddress = id.ipAddress;
        dto.macAddress = id.macAddress;
        dto.hostname = id.hostname;
        dto.dhcpLeaseId = id.dhcpLeaseId;
        dto.firstSeen = id.firstSeen;
        dto.lastSeen = id.lastSeen;
        dto.current = id.current;
        return dto;
    }

    private FingerprintVectorBackup mapToBackup(FingerprintVector f) {
        FingerprintVectorBackup dto = new FingerprintVectorBackup();
        dto.id = f.id;
        dto.version = f.version;
        dto.dhcpOption55 = f.dhcpOption55;
        dto.dhcpOption60 = f.dhcpOption60;
        dto.tcpFingerprint = f.tcpFingerprint;
        if (f.mdnsServices != null) dto.mdnsServices = String.join(",", f.mdnsServices);
        dto.ssdpUsn = f.ssdpUsn;
        dto.sshHostKeys = f.sshHostKeys != null ? new ArrayList<>(f.sshHostKeys) : new ArrayList<>();
        dto.httpServerHeader = f.httpServerHeader;
        dto.tlsJa4 = f.tlsJa4;
        dto.tlsCertSubject = f.tlsCertSubject;
        if (f.openPorts != null) {
            dto.openPorts = f.openPorts.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        dto.macOui = f.macOui;
        dto.capturedAt = f.capturedAt;
        return dto;
    }

    private CredentialBackup mapToBackup(Credential c, boolean includeSecrets) {
        CredentialBackup dto = new CredentialBackup();
        dto.id = c.id;
        dto.label = c.label;
        dto.credentialType = c.credentialType;
        dto.username = c.username;
        dto.port = c.port;
        dto.createdAt = c.createdAt;
        dto.updatedAt = c.updatedAt;
        if (includeSecrets) {
            dto.encryptedPayload = c.encryptedPayload;
            dto.noncePayload = c.noncePayload;
        }
        return dto;
    }

    private NetworkLinkBackup mapToBackup(NetworkLink link) {
        NetworkLinkBackup dto = new NetworkLinkBackup();
        dto.id = link.id;
        dto.sourceDeviceId = link.sourceDevice != null ? link.sourceDevice.id : null;
        dto.targetDeviceId = link.targetDevice != null ? link.targetDevice.id : null;
        dto.sourceInterface = link.sourceInterface;
        dto.targetInterface = link.targetInterface;
        dto.discoveryProtocol = link.discoveryProtocol;
        dto.lastVerified = link.lastVerified;
        return dto;
    }

    // --- Mapping from Backup DTOs ---

    private PhysicalDevice mapFromBackup(PhysicalDeviceBackup dto) {
        PhysicalDevice device = new PhysicalDevice();
        // Skip setting ID manually so @GeneratedValue works
        device.displayName = dto.displayName;
        device.deviceType = dto.deviceType;
        device.osFamily = dto.osFamily;
        device.osVersion = dto.osVersion;
        device.manufacturer = dto.manufacturer;
        device.model = dto.model;
        device.locationNote = dto.locationNote;
        device.confidenceScore = dto.confidenceScore != null ? dto.confidenceScore : 1.0;
        device.manuallyVerified = dto.manuallyVerified != null ? dto.manuallyVerified : false;
        device.firstSeen = dto.firstSeen;
        device.lastSeen = dto.lastSeen;
        device.status = dto.status;
        device.managementState = dto.managementState;
        if (dto.labels != null) device.labels.addAll(dto.labels);

        if (dto.identities != null) {
            for (NetworkIdentityBackup idDto : dto.identities) {
                NetworkIdentity id = mapFromBackup(idDto);
                id.physicalDevice = device;
                device.identities.add(id);
            }
        }
        if (dto.fingerprints != null) {
            for (FingerprintVectorBackup fpDto : dto.fingerprints) {
                FingerprintVector fp = mapFromBackup(fpDto);
                fp.physicalDevice = device;
                device.fingerprints.add(fp);
            }
        }
        if (dto.credentials != null) {
            for (CredentialBackup credDto : dto.credentials) {
                Credential cred = mapFromBackup(credDto);
                cred.physicalDevice = device;
                device.credentials.add(cred);
            }
        }
        return device;
    }

    private NetworkIdentity mapFromBackup(NetworkIdentityBackup dto) {
        NetworkIdentity id = new NetworkIdentity();
        // Skip setting ID manually
        id.ipAddress = dto.ipAddress;
        id.macAddress = dto.macAddress;
        id.hostname = dto.hostname;
        id.dhcpLeaseId = dto.dhcpLeaseId;
        id.firstSeen = dto.firstSeen;
        id.lastSeen = dto.lastSeen;
        id.current = dto.current;
        return id;
    }

    private FingerprintVector mapFromBackup(FingerprintVectorBackup dto) {
        FingerprintVector fp = new FingerprintVector();
        // Skip setting ID manually
        fp.version = dto.version;
        fp.dhcpOption55 = dto.dhcpOption55;
        fp.dhcpOption60 = dto.dhcpOption60;
        fp.tcpFingerprint = dto.tcpFingerprint;
        if (dto.mdnsServices != null && !dto.mdnsServices.isEmpty()) {
            fp.mdnsServices = List.of(dto.mdnsServices.split(","));
        }
        fp.ssdpUsn = dto.ssdpUsn;
        fp.sshHostKeys = dto.sshHostKeys != null ? new ArrayList<>(dto.sshHostKeys) : new ArrayList<>();
        fp.httpServerHeader = dto.httpServerHeader;
        fp.tlsJa4 = dto.tlsJa4;
        fp.tlsCertSubject = dto.tlsCertSubject;
        if (dto.openPorts != null && !dto.openPorts.isEmpty()) {
            fp.openPorts = java.util.Arrays.stream(dto.openPorts.split(","))
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());
        }
        fp.macOui = dto.macOui;
        fp.capturedAt = dto.capturedAt;
        return fp;
    }

    private Credential mapFromBackup(CredentialBackup dto) {
        Credential c = new Credential();
        // Skip setting ID manually
        c.label = dto.label;
        c.credentialType = dto.credentialType;
        c.username = dto.username;
        c.port = dto.port;
        c.createdAt = dto.createdAt;
        c.updatedAt = dto.updatedAt;
        c.encryptedPayload = dto.encryptedPayload != null ? dto.encryptedPayload : new byte[0];
        c.noncePayload = dto.noncePayload != null ? dto.noncePayload : new byte[0];
        return c;
    }

    private NetworkLink mapFromBackup(NetworkLinkBackup dto) {
        NetworkLink link = new NetworkLink();
        // Skip setting ID manually
        
        if (dto.sourceDeviceId != null) {
            link.sourceDevice = PhysicalDevice.findById(dto.sourceDeviceId);
        }
        if (dto.targetDeviceId != null) {
            link.targetDevice = PhysicalDevice.findById(dto.targetDeviceId);
        }
        
        link.sourceInterface = dto.sourceInterface;
        link.targetInterface = dto.targetInterface;
        link.discoveryProtocol = dto.discoveryProtocol;
        link.lastVerified = dto.lastVerified;
        return link;
    }

    // --- NEW FULL BACKUP LOGIC (ZIP + AES-256-GCM) ---
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(BackupService.class);

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.username")
    String dbUser;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.datasource.password")
    String dbPassword;

    private static final int SALT_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String KEYS_DIR = "keys";
    private static final String DUMP_FILE_NAME = "database_dump.sql";

    public java.nio.file.Path createBackup(String password) throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("gnm_backup_");
        java.nio.file.Path dumpFile = tempDir.resolve(DUMP_FILE_NAME);
        try {
            runPgDump(dumpFile);
            java.nio.file.Path zipFile = tempDir.resolve("backup.zip");
            createZip(dumpFile, zipFile);
            java.nio.file.Path encryptedFile = java.nio.file.Files.createTempFile("gnm_backup_", ".gnmbak");
            encryptFile(zipFile, encryptedFile, password);
            return encryptedFile;
        } finally {
            deleteDirectory(tempDir);
        }
    }

    public void restoreBackup(java.nio.file.Path encryptedBackup, String password) throws Exception {
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("gnm_restore_");
        java.nio.file.Path zipFile = tempDir.resolve("backup.zip");
        try {
            decryptFile(encryptedBackup, zipFile, password);
            unzip(zipFile, tempDir);
            java.nio.file.Path restoredKeysDir = tempDir.resolve(KEYS_DIR);
            if (java.nio.file.Files.exists(restoredKeysDir)) {
                java.nio.file.Path actualKeysDir = java.nio.file.Paths.get(KEYS_DIR);
                if (java.nio.file.Files.exists(actualKeysDir)) {
                    deleteDirectory(actualKeysDir);
                }
                java.nio.file.Files.move(restoredKeysDir, actualKeysDir, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                LOG.info("Restored keys directory.");
            }
            java.nio.file.Path dumpFile = tempDir.resolve(DUMP_FILE_NAME);
            if (java.nio.file.Files.exists(dumpFile)) {
                runPsqlRestore(dumpFile);
                LOG.info("Restored database.");
            } else {
                throw new IllegalStateException("Backup does not contain database dump.");
            }
            LOG.info("Backup restored successfully. Restarting application...");
            System.exit(0);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    private void runPgDump(java.nio.file.Path outputFile) throws Exception {
        DbConnectionInfo info = parseJdbcUrl(jdbcUrl);
        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump", "-h", info.host, "-p", String.valueOf(info.port), 
                "-U", dbUser, "-d", info.dbName, "-f", outputFile.toAbsolutePath().toString(),
                "-c", "--if-exists"
        );
        java.util.Map<String, String> env = pb.environment();
        env.put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("pg_dump failed with exit code " + exitCode + ": " + output);
    }

    private void runPsqlRestore(java.nio.file.Path inputFile) throws Exception {
        DbConnectionInfo info = parseJdbcUrl(jdbcUrl);
        ProcessBuilder pb = new ProcessBuilder(
                "psql", "-h", info.host, "-p", String.valueOf(info.port), 
                "-U", dbUser, "-d", info.dbName, "-f", inputFile.toAbsolutePath().toString(),
                "-v", "ON_ERROR_STOP=1"
        );
        java.util.Map<String, String> env = pb.environment();
        env.put("PGPASSWORD", dbPassword);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("psql restore failed with exit code " + exitCode + ": " + output);
    }

    private void createZip(java.nio.file.Path dumpFile, java.nio.file.Path zipFile) throws Exception {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile.toFile()))) {
            addFileToZip(dumpFile, DUMP_FILE_NAME, zos);
            java.nio.file.Path keysDir = java.nio.file.Paths.get(KEYS_DIR);
            if (java.nio.file.Files.exists(keysDir)) {
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(keysDir)) {
                    stream.filter(path -> !java.nio.file.Files.isDirectory(path)).forEach(path -> {
                        try {
                            String zipEntryName = keysDir.getParent() == null ? path.toString() : keysDir.getParent().relativize(path).toString();
                            zipEntryName = zipEntryName.replace('\\', '/');
                            addFileToZip(path, zipEntryName, zos);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
        }
    }

    private void addFileToZip(java.nio.file.Path file, String zipEntryName, java.util.zip.ZipOutputStream zos) throws Exception {
        java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(zipEntryName);
        zos.putNextEntry(zipEntry);
        java.nio.file.Files.copy(file, zos);
        zos.closeEntry();
    }

    private void unzip(java.nio.file.Path zipFile, java.nio.file.Path destDir) throws Exception {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.FileInputStream(zipFile.toFile()))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                java.nio.file.Path resolvedPath = destDir.resolve(entry.getName()).normalize();
                if (!resolvedPath.startsWith(destDir)) throw new RuntimeException("Zip slip vulnerability detected: " + entry.getName());
                if (entry.isDirectory()) {
                    java.nio.file.Files.createDirectories(resolvedPath);
                } else {
                    java.nio.file.Files.createDirectories(resolvedPath.getParent());
                    java.nio.file.Files.copy(zis, resolvedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void encryptFile(java.nio.file.Path inputFile, java.nio.file.Path outputFile, String password) throws Exception {
        byte[] salt = new byte[SALT_LENGTH];
        new java.security.SecureRandom().nextBytes(salt);
        byte[] kekBytes = deriveKek(password, salt);
        javax.crypto.SecretKey kek = new javax.crypto.spec.SecretKeySpec(kekBytes, "AES");
        byte[] iv = new byte[GCM_IV_LENGTH];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, kek, spec);
        try (java.io.OutputStream fos = new java.io.FileOutputStream(outputFile.toFile());
             javax.crypto.CipherOutputStream cos = new javax.crypto.CipherOutputStream(fos, cipher)) {
            fos.write(salt);
            fos.write(iv);
            java.nio.file.Files.copy(inputFile, cos);
        }
    }

    private void decryptFile(java.nio.file.Path inputFile, java.nio.file.Path outputFile, String password) throws Exception {
        try (java.io.InputStream fis = new java.io.FileInputStream(inputFile.toFile())) {
            byte[] salt = new byte[SALT_LENGTH];
            if (fis.read(salt) != SALT_LENGTH) throw new IllegalArgumentException("Invalid backup file: missing salt");
            byte[] iv = new byte[GCM_IV_LENGTH];
            if (fis.read(iv) != GCM_IV_LENGTH) throw new IllegalArgumentException("Invalid backup file: missing IV");
            byte[] kekBytes = deriveKek(password, salt);
            javax.crypto.SecretKey kek = new javax.crypto.spec.SecretKeySpec(kekBytes, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kek, spec);
            try (javax.crypto.CipherInputStream cis = new javax.crypto.CipherInputStream(fis, cipher)) {
                java.nio.file.Files.copy(cis, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                if (e.getCause() instanceof javax.crypto.AEADBadTagException) {
                    throw new IllegalArgumentException("Incorrect backup password", e);
                }
                throw e;
            }
        }
    }

    private byte[] deriveKek(String passcode, byte[] salt) {
        org.bouncycastle.crypto.params.Argon2Parameters.Builder builder = new org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
                .withVersion(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(65536)
                .withParallelism(4)
                .withSalt(salt);
        org.bouncycastle.crypto.generators.Argon2BytesGenerator gen = new org.bouncycastle.crypto.generators.Argon2BytesGenerator();
        gen.init(builder.build());
        byte[] result = new byte[32];
        gen.generateBytes(passcode.getBytes(java.nio.charset.StandardCharsets.UTF_8), result, 0, result.length);
        return result;
    }

    private void deleteDirectory(java.nio.file.Path path) {
        if (!java.nio.file.Files.exists(path)) return;
        try {
            java.nio.file.Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                    java.nio.file.Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path dir, java.io.IOException exc) throws java.io.IOException {
                    java.nio.file.Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            LOG.warn("Failed to cleanup temp directory: " + path, e);
        }
    }

    private static class DbConnectionInfo {
        String host;
        int port;
        String dbName;
    }

    private DbConnectionInfo parseJdbcUrl(String url) {
        String cleanUrl = url.replace("jdbc:", "");
        java.net.URI uri = java.net.URI.create(cleanUrl);
        DbConnectionInfo info = new DbConnectionInfo();
        info.host = uri.getHost();
        info.port = uri.getPort() == -1 ? 5432 : uri.getPort();
        info.dbName = uri.getPath().substring(1);
        return info;
    }
}
