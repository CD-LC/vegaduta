package com.agenticai.core.omnichannel.teams;

public enum TeamsCredentialStatus {
    ACTIVE,
    INVALID,
    // Tenant-initiated disconnect, distinct from INVALID (credential proved bad): the token may
    // still be valid - the tenant just asked us to stop using it.
    DISCONNECTED
}
