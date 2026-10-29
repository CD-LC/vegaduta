package com.agenticai.core.omnichannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.approval.RetryableActionFailure;
import org.junit.jupiter.api.Test;

/**
 * The channel exception family: every subtype carries the channel name so a failure reaching the
 * central handler still says which channel it came from.
 */
class ChannelExceptionsTest {

    @Test
    void deliveryExceptionCarriesChannelMessageAndCause() {
        ChannelDeliveryException plain = new ChannelDeliveryException("whatsapp", "send failed");
        assertEquals("whatsapp", plain.getChannelName());
        assertEquals("send failed", plain.getMessage());

        RuntimeException cause = new RuntimeException("io");
        ChannelDeliveryException withCause =
                new ChannelDeliveryException("telegram", "send failed", cause);
        assertSame(cause, withCause.getCause());
        assertEquals("telegram", withCause.getChannelName());
    }

    @Test
    void configurationExceptionIsAChannelException() {
        ChannelConfigurationException e =
                new ChannelConfigurationException("signal", "bad number");
        assertEquals("signal", e.getChannelName());
        assertTrue(e instanceof ChannelException);
    }

    @Test
    void retryableDeliveryIsMarkedForTheApprovalGate() {
        RetryableChannelDeliveryException e =
                new RetryableChannelDeliveryException(
                        "whatsapp", "upstream timeout", new RuntimeException("t"));
        assertTrue(e instanceof RetryableActionFailure);
        assertEquals("whatsapp", e.getChannelName());
    }

    @Test
    void payloadSerializationExceptionWrapsTheCause() {
        RuntimeException cause = new RuntimeException("jackson");
        ChannelPayloadSerializationException e =
                new ChannelPayloadSerializationException("email", "cannot serialize", cause);
        assertEquals("email", e.getChannelName());
        assertSame(cause, e.getCause());
    }

    @Test
    void whatsAppDeliveryRejectedCarriesTheMetaErrorCode() {
        WhatsAppDeliveryRejectedException e =
                new WhatsAppDeliveryRejectedException("limit reached", 131049);
        assertEquals(131049, e.getMetaCode());
        assertEquals("whatsapp", e.getChannelName());
        assertEquals("limit reached", e.getMessage());
        assertTrue(e instanceof ChannelDeliveryException);
    }
}
