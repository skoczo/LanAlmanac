package com.gnm.pkgmgmt.client;

import com.gnm.model.Credential;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialCategory;
import com.gnm.model.enums.CredentialType;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CredentialResolver {

    public record ResolvedCandidate(
            Credential credential,
            NetworkService sshService,
            int effectivePort,
            boolean fallback,
            String resolutionSource) {}

    /**
     * Resolves an ordered list of at most 2 SSH credential candidates for package management:
     * 1. {@code PKG_MANAGEMENT} (primary)
     * 2. {@code PKG_MANAGEMENT_FALLBACK} (fallback)
     * 3. If neither is present, falls back to {@code NetworkService(SSH).credential}.
     */
    public List<ResolvedCandidate> resolveCandidates(PhysicalDevice device) {
        if (device == null) {
            return List.of();
        }
        NetworkService sshService = findSshService(device).orElse(null);
        int servicePort = (sshService != null && sshService.port != null) ? sshService.port : 22;

        List<Credential> sshCreds =
                device.credentials == null
                        ? List.of()
                        : device.credentials.stream()
                                .filter(CredentialResolver::isSshCompatible)
                                .toList();

        Optional<Credential> primary =
                sshCreds.stream()
                        .filter(
                                c ->
                                        c.categories != null
                                                && c.categories.contains(
                                                        CredentialCategory.PKG_MANAGEMENT))
                        .findFirst();

        Optional<Credential> fallback =
                sshCreds.stream()
                        .filter(
                                c ->
                                        c.categories != null
                                                && c.categories.contains(
                                                        CredentialCategory.PKG_MANAGEMENT_FALLBACK))
                        .findFirst();

        List<ResolvedCandidate> candidates = new ArrayList<>(2);
        primary.ifPresent(
                c ->
                        candidates.add(
                                new ResolvedCandidate(
                                        c,
                                        sshService,
                                        effectivePort(c, servicePort),
                                        false,
                                        "PKG_MANAGEMENT")));

        if (fallback.isPresent()) {
            Credential fb = fallback.get();
            if (primary.isEmpty() || !primary.get().id.equals(fb.id)) {
                candidates.add(
                        new ResolvedCandidate(
                                fb,
                                sshService,
                                effectivePort(fb, servicePort),
                                primary.isPresent(),
                                "PKG_MANAGEMENT_FALLBACK"));
            }
        }

        if (candidates.isEmpty()
                && sshService != null
                && isSshCompatible(sshService.credential)) {
            Credential svcCred = sshService.credential;
            candidates.add(
                    new ResolvedCandidate(
                            svcCred,
                            sshService,
                            effectivePort(svcCred, servicePort),
                            false,
                            "SSH_SERVICE_FALLBACK"));
        }

        return List.copyOf(candidates);
    }

    public static Optional<NetworkService> findSshService(PhysicalDevice device) {
        if (device == null || device.services == null) {
            return Optional.empty();
        }
        return device.services.stream()
                .filter(s -> s != null && "SSH".equalsIgnoreCase(s.serviceType))
                .findFirst();
    }

    private static boolean isSshCompatible(Credential c) {
        return c != null
                && (c.credentialType == CredentialType.SSH_KEY
                        || c.credentialType == CredentialType.PASSWORD);
    }

    private static int effectivePort(Credential c, int defaultPort) {
        return (c.port != null && c.port > 0) ? c.port : defaultPort;
    }

    public static void initializeForExecution(PhysicalDevice device) {
        if (device == null) {
            return;
        }
        if (device.identities != null) {
            org.hibernate.Hibernate.initialize(device.identities);
        }
        if (device.services != null) {
            org.hibernate.Hibernate.initialize(device.services);
            for (NetworkService s : device.services) {
                if (s != null && s.credential != null) {
                    org.hibernate.Hibernate.initialize(s.credential);
                    if (s.credential.categories != null) {
                        org.hibernate.Hibernate.initialize(s.credential.categories);
                    }
                }
            }
        }
        if (device.credentials != null) {
            org.hibernate.Hibernate.initialize(device.credentials);
            for (Credential c : device.credentials) {
                if (c != null && c.categories != null) {
                    org.hibernate.Hibernate.initialize(c.categories);
                }
            }
        }
    }
}

