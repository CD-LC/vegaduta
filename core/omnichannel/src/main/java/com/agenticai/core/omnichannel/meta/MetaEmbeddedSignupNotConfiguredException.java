package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * Thrown when a tenant tries the "Connect WhatsApp (guided setup)" button but the PLATFORM operator
 * (not the tenant) hasn't registered a Meta App for Embedded Signup yet - see {@code
 * MetaEmbeddedSignupService}'s javadoc for the one-time setup this represents. Maps to 400: from
 * the tenant's point of view this reads as "guided setup isn't available right now, use manual
 * setup below instead" (the manual BYOK form stays fully usable regardless of this).
 */
public class MetaEmbeddedSignupNotConfiguredException extends RuntimeException
        implements HttpStatusMapped {

    public MetaEmbeddedSignupNotConfiguredException() {
        super(
                "Guided WhatsApp setup isn't configured on this platform yet - use the manual setup form below instead. "
                        + "(Platform operator: set META_EMBEDDED_SIGNUP_APP_ID/APP_SECRET/CONFIG_ID/REDIRECT_URI.)");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
