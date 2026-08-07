package com.gnm.resource;

import com.gnm.model.GlobalSetting;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {

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
        return setting;
    }

    @GET
    @Path("/public/oidc")
    public java.util.Map<String, String> getPublicOidcSettings() {
        GlobalSetting enabled = GlobalSetting.findById("oidc.enabled");
        GlobalSetting url = GlobalSetting.findById("oidc.authority.url");
        GlobalSetting clientId = GlobalSetting.findById("oidc.client.id");

        return java.util.Map.of(
            "enabled", enabled != null && "true".equalsIgnoreCase(enabled.value) ? "true" : "false",
            "authority", url != null ? url.value : "",
            "clientId", clientId != null ? clientId.value : ""
        );
    }
}
