package com.agenticai.core.omnichannel.twilio;

public enum TwilioCredentialStatus {
    ACTIVE,
    INVALID,
    // A tenant-initiated disconnect, distinct from INVALID
    // (which means "we tried to use this and Twilio rejected it", not
    // "the tenant asked us to stop using it"). The row itself is kept, not
    // deleted, so TwilioCredentialController#current can still show what was
    // connected and the tenant can reconnect without re-entering everything.
    DISCONNECTED
}
