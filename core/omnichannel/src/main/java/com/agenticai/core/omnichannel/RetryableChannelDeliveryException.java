package com.agenticai.core.omnichannel;

import com.agenticai.core.approval.RetryableActionFailure;

/**
 * A {@link ChannelDeliveryException} specifically for the "the channel's
 * API call itself failed" case (network blip, transient 5xx, timeout) -
 * distinct from {@link ChannelDeliveryException}'s other throw sites in
 * {@code TwilioWhatsAppChannelAdapter} (malformed payload, missing
 * recipient), which stay plain {@link ChannelDeliveryException} because
 * retrying a bad payload can never succeed. Implementing {@link
 * RetryableActionFailure} is what makes {@code HumanApprovalGateService}
 * schedule a retry instead of failing permanently on the first attempt.
 */
public class RetryableChannelDeliveryException extends ChannelDeliveryException implements RetryableActionFailure {

    public RetryableChannelDeliveryException(String channelName, String message, Throwable cause) {
        super(channelName, message, cause);
    }
}
