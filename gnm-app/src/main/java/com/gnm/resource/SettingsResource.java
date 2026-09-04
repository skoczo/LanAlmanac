package com.gnm.resource;

import com.gnm.model.GlobalSetting;
import com.gnm.model.SettingChangedEvent;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.stream.Collectors;


@Path("/api/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {

    @Inject
    Event<SettingChangedEvent> settingChangedEvent;

    @GET
    public List<GlobalSetting> getAllSettings() {
        return GlobalSetting.listAll();
    }

    @PUT
    @Path("/{key}")
    @Transactional
    public GlobalSetting updateSetting(@PathParam("key") String key, GlobalSetting update) {
        GlobalSetting setting = GlobalSetting.findById(key);
        if (setting == null) {
            setting = new GlobalSetting();
            setting.key = key;
        }
        setting.value = update.value;
        setting.persist();
        
        settingChangedEvent.fire(new SettingChangedEvent(key, update.value));
        
        return setting;
    }

    @GET
    @Path("/interfaces")
    public List<String> getNetworkInterfaces() throws SocketException {
        return Collections.list(NetworkInterface.getNetworkInterfaces())
                .stream()
                .filter(ni -> {
                    try {
                        return ni.isUp() && !ni.isLoopback();
                    } catch (SocketException e) {
                        return false;
                    }
                })
                .map(NetworkInterface::getName)
                .collect(Collectors.toList());
    }

    @GET
    @Path("/public/oidc")
    public java.util.Map<String, String> getPublicOidcSettings() {
        GlobalSetting enabled = GlobalSetting.findById("oidc.enabled");
        GlobalSetting url = GlobalSetting.findById("oidc.authority.url");
        GlobalSetting clientId = GlobalSetting.findById("oidc.client.id");
        GlobalSetting roleClaimPath = GlobalSetting.findById("oidc.role.claim.path");

        return java.util.Map.of(
            "enabled", enabled != null && "true".equalsIgnoreCase(enabled.value) ? "true" : "false",
            "authority", url != null ? url.value : "",
            "clientId", clientId != null ? clientId.value : "",
            "roleClaimPath", roleClaimPath != null && !roleClaimPath.value.isBlank() ? roleClaimPath.value : "groups"
        );
    }
}
