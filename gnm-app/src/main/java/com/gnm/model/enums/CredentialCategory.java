package com.gnm.model.enums;

public enum CredentialCategory {
    PKG_MANAGEMENT(true),
    PKG_MANAGEMENT_FALLBACK(true),
    SSH_TERMINAL(false),
    WEB_ADMIN(false),
    MONITORING(false),
    API(false),
    GENERAL(false);

    private final boolean exclusivePerDevice;

    CredentialCategory(boolean exclusivePerDevice) {
        this.exclusivePerDevice = exclusivePerDevice;
    }

    public boolean isExclusivePerDevice() {
        return exclusivePerDevice;
    }
}

