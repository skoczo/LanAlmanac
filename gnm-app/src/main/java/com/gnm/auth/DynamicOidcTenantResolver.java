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

    @Override
    public Uni<OidcTenantConfig> resolve(RoutingContext routingContext,
            io.quarkus.oidc.OidcRequestContext<OidcTenantConfig> requestContext) {
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

                return OidcTenantConfig.builder()
                        .tenantId("dynamic-oidc")
                        .authServerUrl(authUrl.value)
                        .clientId(clientId.value)
                        .applicationType(io.quarkus.oidc.runtime.OidcTenantConfig.ApplicationType.SERVICE)
                        .build();
            });
        }).runSubscriptionOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }
}
