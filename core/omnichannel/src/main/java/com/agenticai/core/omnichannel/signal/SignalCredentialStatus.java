package com.agenticai.core.omnichannel.signal;

public enum SignalCredentialStatus {
    ACTIVE,
    INVALID,
    // Tenant-initiated disconnect, distinct from INVALID: the link to signal-cli may still work, the tenant just asked us to stop using it.
    DISCONNECTED
}
