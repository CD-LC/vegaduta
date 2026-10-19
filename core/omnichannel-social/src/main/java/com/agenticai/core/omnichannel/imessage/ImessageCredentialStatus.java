package com.agenticai.core.omnichannel.imessage;

public enum ImessageCredentialStatus {
    ACTIVE,
    // Tenant-initiated disconnect: the BlueBubbles server may be fine, the tenant just asked us to stop using it.
    DISCONNECTED
}
