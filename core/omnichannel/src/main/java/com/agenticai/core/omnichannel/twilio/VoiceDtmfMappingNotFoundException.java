package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.security.http.HttpStatusMapped;

/**
 * No {@link VoiceDtmfMapping} exists for the given digit - {@code DELETE
 * /api/channels/voice/dtmf-menu/{digit}} on an unmapped digit.
 */
public class VoiceDtmfMappingNotFoundException extends RuntimeException
        implements HttpStatusMapped {

    public VoiceDtmfMappingNotFoundException(String digit) {
        super("No DTMF mapping found for digit '" + digit + "'");
    }

    /** 404: no such mapping. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 404;
    }
}
