package com.gnm.pkgmgmt.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gnm.model.Credential;
import com.gnm.model.NetworkService;
import com.gnm.model.PhysicalDevice;
import com.gnm.model.enums.CredentialCategory;
import com.gnm.model.enums.CredentialType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialResolverTest {

    private final CredentialResolver resolver = new CredentialResolver();

    private static Credential cred(
            String username, CredentialType type, CredentialCategory... categories) {
        Credential c = new Credential();
        c.id = UUID.randomUUID();
        c.label = username;
        c.username = username;
        c.credentialType = type;
        c.encryptedPayload = new byte[] {1, 2, 3};
        c.noncePayload = new byte[] {4, 5, 6};
        c.categories = Set.of(categories);
        return c;
    }

    @Test
    void prefersPkgManagementOverFallbackAndSshServiceCredential() {
        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();

        Credential webCred = cred("web-admin", CredentialType.PASSWORD, CredentialCategory.WEB_ADMIN);
        Credential sshSvcCred = cred("ssh-default", CredentialType.SSH_KEY, CredentialCategory.SSH_TERMINAL);
        Credential fallbackCred =
                cred("pkg-backup", CredentialType.SSH_KEY, CredentialCategory.PKG_MANAGEMENT_FALLBACK);
        Credential primaryCred =
                cred(
                        "pkg-primary",
                        CredentialType.SSH_KEY,
                        CredentialCategory.SSH_TERMINAL,
                        CredentialCategory.PKG_MANAGEMENT);

        device.credentials = List.of(webCred, sshSvcCred, fallbackCred, primaryCred);

        NetworkService sshSvc = new NetworkService();
        sshSvc.id = UUID.randomUUID();
        sshSvc.serviceType = "SSH";
        sshSvc.port = 22;
        sshSvc.credential = sshSvcCred;
        device.services = Set.of(sshSvc);

        var candidates = resolver.resolveCandidates(device);
        assertEquals(2, candidates.size());
        assertEquals(primaryCred.id, candidates.get(0).credential().id);
        assertFalse(candidates.get(0).fallback());
        assertEquals(fallbackCred.id, candidates.get(1).credential().id);
        assertTrue(candidates.get(1).fallback());
    }

    @Test
    void fallsBackToPkgManagementFallbackThenSshServiceCredentialWhenPrimaryMissing() {
        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();

        Credential sshSvcCred = cred("ubuntu", CredentialType.PASSWORD, CredentialCategory.GENERAL);
        device.credentials = List.of(sshSvcCred);

        NetworkService sshSvc = new NetworkService();
        sshSvc.id = UUID.randomUUID();
        sshSvc.serviceType = "SSH";
        sshSvc.port = 2222;
        sshSvc.credential = sshSvcCred;
        device.services = Set.of(sshSvc);

        var candidates = resolver.resolveCandidates(device);
        assertEquals(1, candidates.size());
        assertEquals(sshSvcCred.id, candidates.get(0).credential().id);
        assertEquals(2222, candidates.get(0).effectivePort());
    }

    @Test
    void ignoresNonSshCredentialTypesAndReturnsEmptyWhenNoneConfigured() {
        PhysicalDevice device = new PhysicalDevice();
        device.id = UUID.randomUUID();

        Credential apiToken = cred("token", CredentialType.API_TOKEN, CredentialCategory.PKG_MANAGEMENT);
        Credential webPass = cred("admin", CredentialType.PASSWORD, CredentialCategory.WEB_ADMIN);
        device.credentials = List.of(apiToken, webPass);

        var candidates = resolver.resolveCandidates(device);
        assertTrue(candidates.isEmpty());
    }
}

