package com.agenticai.core.omnichannel;

/**
 * A channel is missing or has invalid credentials/configuration - the
 * problem is on our side (nothing was attempted against the channel's API
 * yet, or a webhook signature failed to validate), not the channel's.
 * Mapped to 400 by GlobalExceptionHandler.
 */
public class ChannelConfigurationException extends ChannelException {

    public ChannelConfigurationException(String channelName, String message) {
        super(channelName, message);
    }
}
