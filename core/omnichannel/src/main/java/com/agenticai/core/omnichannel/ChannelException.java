package com.agenticai.core.omnichannel;

/**
 * Base type for any failure raised by a messaging channel adapter (Twilio
 * WhatsApp, Telegram, and whatever else joins the "any other social media"
 * ask later - the
 * error-handling pattern is decided once, here, before the first real
 * channel is built, rather than every adapter inventing its own.
 */
public abstract class ChannelException extends RuntimeException {

    private final String channelName;

    protected ChannelException(String channelName, String message) {
        super(message);
        this.channelName = channelName;
    }

    protected ChannelException(String channelName, String message, Throwable cause) {
        super(message, cause);
        this.channelName = channelName;
    }

    public String getChannelName() {
        return channelName;
    }
}
