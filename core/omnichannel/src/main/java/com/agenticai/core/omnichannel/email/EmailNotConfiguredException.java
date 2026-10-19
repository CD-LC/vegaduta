package com.agenticai.core.omnichannel.email;

import com.agenticai.core.security.http.HttpStatusMapped;

public class EmailNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public EmailNotConfiguredException() {
        super(
                "No active email/IMAP mailbox connected - POST /api/channels/email/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
