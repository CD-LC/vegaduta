package com.agenticai.core.omnichannel;

/**
 * A {@code *SendController}'s outbound send payload could not be serialized
 * to JSON before {@link com.agenticai.core.approval.HumanApprovalGateService
 * #evaluate} was even called - a local (de)serialization failure, not a
 * channel API failure (contrast {@link ChannelDeliveryException}, which is
 * about the channel's own API rejecting a send) and not a missing/invalid
 * configuration problem (contrast {@link ChannelConfigurationException}).
 *
 * <p>In practice this should be unreachable - every {@code SendController}'s
 * {@code SendPayload} is a small record of plain strings that {@code
 * ObjectMapper} always knows how to serialize - so it's mapped to 500 by
 * {@code GlobalExceptionHandler} and recorded on the Issues portal like the
 * generic catch-all, rather than treated as a routine, expected condition.
 * Previously each of the 7 {@code *SendController} classes threw a bare
 * {@link IllegalStateException} here, which fell through to the generic
 * catch-all handler and lost the channel context.
 */
public class ChannelPayloadSerializationException extends ChannelException {

    public ChannelPayloadSerializationException(String channelName, String message, Throwable cause) {
        super(channelName, message, cause);
    }
}
