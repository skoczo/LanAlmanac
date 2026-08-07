package com.gnm.auth;

import com.gnm.model.GlobalSetting;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.OidcTenantConfig.ApplicationType;
import io.quarkus.oidc.TenantConfigResolver;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DynamicOidcTenantResolver implements TenantConfigResolver {

    @SuppressWarnings("removal")
    @Override
    public Uni<OidcTenantConfig> resolve(RoutingContext routingContext, io.quarkus.oidc.OidcRequestContext<OidcTenantConfig> requestContext) {
        return Uni.createFrom().item(() -> {
            return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
                GlobalSetting enabled = GlobalSetting.findById("oidc.enabled");
                if (enabled == null || !"true".equalsIgnoreCase(enabled.value)) {
                    return null;
                }

                GlobalSetting authUrl = GlobalSetting.findById("oidc.authority.url");
                GlobalSetting clientId = GlobalSetting.findById("oidc.client.id");

                if (authUrl == null || clientId == null || authUrl.value.isBlank() || clientId.value.isBlank()) {
                    return null;
                }

                OidcTenantConfig config = new OidcTenantConfig();
                config.setTenantId("dynamic-oidc");
                config.setAuthServerUrl(authUrl.value);
                config.setClientId(clientId.value);
                config.setApplicationType(ApplicationType.SERVICE);
                return config;
            });
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }
}
