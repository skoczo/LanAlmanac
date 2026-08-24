package com.gnm.resource;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class SpaRoutingFilter {

    public void init(@Observes Router router) {
        router.get().handler(rc -> {
            String path = rc.request().path();
            if (!path.startsWith("/api") &&
                !path.startsWith("/q/") &&
                !path.startsWith("/ws") &&
                !path.contains(".")) {
                rc.reroute("/index.html");
            } else {
                rc.next();
            }
        });
    }
}
