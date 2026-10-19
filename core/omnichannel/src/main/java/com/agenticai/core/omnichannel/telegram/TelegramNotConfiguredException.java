package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.security.http.HttpStatusMapped;

public class TelegramNotConfiguredException extends RuntimeException implements HttpStatusMapped {

    public TelegramNotConfiguredException() {
        super("No active Telegram bot connected - POST /api/channels/telegram/credential first.");
    }

    /** 400: client-actionable, not a server bug. Answered by the central exception handler. */
    @Override
    public int httpStatus() {
        return 400;
    }
}
