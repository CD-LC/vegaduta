package com.agenticai.core.omnichannel;

/**
 * A message could not be delivered through a channel - the channel's own
 * API rejected the send (rate limit, invalid recipient, unapproved
 * template, etc.). Mapped to 502 by GlobalExceptionHandler: our server made
 * an outbound call to a third-party API that failed, the same reasoning
 * already applied to UpstreamProviderException (LLM calls) and
 * SidecarCommunicationException (the Python sidecar).
 */
public class ChannelDeliveryException extends ChannelException {

    public ChannelDeliveryException(String channelName, String message) {
        super(channelName, message);
    }

    public ChannelDeliveryException(String channelName, String message, Throwable cause) {
        super(channelName, message, cause);
    }
}
