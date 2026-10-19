package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.security.http.HttpStatusMapped;

public class SlackNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public SlackNotConfiguredException() {
        super("No active Slack App connected - POST /api/channels/slack/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
