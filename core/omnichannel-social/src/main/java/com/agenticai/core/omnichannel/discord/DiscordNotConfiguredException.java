package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.security.http.HttpStatusMapped;

public class DiscordNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public DiscordNotConfiguredException() {
        super("No active Discord bot connected - POST /api/channels/discord/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
