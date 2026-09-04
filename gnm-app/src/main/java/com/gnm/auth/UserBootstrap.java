package com.gnm.auth;

import com.gnm.model.GnmUser;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Seeds the initial admin user from environment/config on first startup.
 * If the admin user already exists in the database, this does nothing —
 * so restarting the app won't reset a changed password.
 */
@ApplicationScoped
public class UserBootstrap {

    private static final Logger LOG = Logger.getLogger(UserBootstrap.class);

    @Inject
    PasswordService passwordService;

    @ConfigProperty(name = "gnm.auth.local.username", defaultValue = "admin")
    String adminUsername;

    @ConfigProperty(name = "gnm.auth.local.password", defaultValue = "admin")
    String adminPassword;

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        GnmUser existing = GnmUser.findByUsername(adminUsername);
        if (existing != null) {
            LOG.infof("Admin user '%s' already exists, skipping bootstrap.", adminUsername);
            return;
        }

        GnmUser admin = new GnmUser();
        admin.username = adminUsername;
        admin.passwordHash = passwordService.hashPassword(adminPassword);
        admin.displayName = "Administrator";
        admin.role = "gnm-admin";
        admin.mustChangePassword = true;
        admin.enabled = true;
        admin.persist();

        LOG.infof("Created initial admin user '%s' with must_change_password=true.", adminUsername);
    }
}
