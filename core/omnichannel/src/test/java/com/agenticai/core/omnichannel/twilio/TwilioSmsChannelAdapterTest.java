package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.RetryableChannelDeliveryException;
import com.agenticai.core.omnichannel.SmsDispatchService;
import com.agenticai.core.omnichannel.session.ChannelOutboundLogger;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.junit.jupiter.api.Test;

/** The approval-gated SMS send: body fitting, the outbound log row, and the retry posture. */
class TwilioSmsChannelAdapterTest {

    private final SmsDispatchService dispatch = mock(SmsDispatchService.class);
    private final ChannelOutboundLogger outboundLogger = mock(ChannelOutboundLogger.class);

    @Test
    void shortReplyIsSentUnchangedAndLoggedAsSms() {
        TwilioSmsChannelAdapter adapter = new TwilioSmsChannelAdapter(dispatch, outboundLogger, 1500);
        when(dispatch.send("+15550001111", "Hello there.")).thenReturn("Sent SMS");

        adapter.execute(request("{\"to\":\"+15550001111\",\"body\":\"Hello there.\"}", "system:sms-inbound:x"));

        verify(dispatch).send("+15550001111", "Hello there.");
        verify(outboundLogger)
                .recordSent(ChannelType.SMS, "+15550001111", "Hello there.", "system:sms-inbound:x");
    }

    @Test
    void longReplyIsCutAtASentenceBoundaryBeforeTheLimit() {
        String text = "First sentence is here. Second sentence follows it. Third one is the longest of all and keeps going.";
        String cut = TwilioSmsChannelAdapter.fitToSms(text, 60);

        assertEquals("First sentence is here. Second sentence follows it.…", cut);
        assertTrue(cut.length() <= 60);
    }

    @Test
    void longReplyWithoutSentenceEndFallsBackToAWordBoundary() {
        String text = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu";
        String cut = TwilioSmsChannelAdapter.fitToSms(text, 40);

        assertTrue(cut.length() <= 40, cut);
        assertTrue(cut.endsWith("…"));
        assertTrue(text.startsWith(cut.substring(0, cut.length() - 1)));
        assertTrue(!cut.contains("  "));
    }

    @Test
    void blankLineRunsAreCollapsedEvenWhenTheTextFits() {
        assertEquals("a\n\nb", TwilioSmsChannelAdapter.fitToSms("a\n\n\n\n\nb\n", 100));
    }

    @Test
    void configuredLimitIsClampedToTwilioCeiling() {
        TwilioSmsChannelAdapter adapter = new TwilioSmsChannelAdapter(dispatch, outboundLogger, 9_000);
        String body = "x".repeat(3_000);
        when(dispatch.send(eq("+15550001111"), any())).thenReturn("Sent SMS");

        adapter.execute(request("{\"to\":\"+15550001111\",\"body\":\"" + body + "\"}", "u"));

        verify(dispatch).send(eq("+15550001111"), org.mockito.ArgumentMatchers.argThat(
                sent -> sent.length() <= TwilioSmsChannelAdapter.TWILIO_MAX_BODY_CHARS));
    }

    @Test
    void providerFailureIsRetryable() {
        TwilioSmsChannelAdapter adapter = new TwilioSmsChannelAdapter(dispatch, outboundLogger, 1500);
        when(dispatch.send(any(), any()))
                .thenThrow(new ChannelDeliveryException("sms", "Twilio SMS send failed: 503"));

        assertThrows(
                RetryableChannelDeliveryException.class,
                () -> adapter.execute(request("{\"to\":\"+15550001111\",\"body\":\"hi\"}", "u")));
    }

    @Test
    void missingPayloadIsNotRetried() {
        TwilioSmsChannelAdapter adapter = new TwilioSmsChannelAdapter(dispatch, outboundLogger, 1500);
        assertThrows(ChannelDeliveryException.class, () -> adapter.execute(request("", "u")));
    }

    private static ApprovalRequest request(String payloadJson, String requestedBy) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getPayloadJson()).thenReturn(payloadJson);
        when(request.getRequestedBy()).thenReturn(requestedBy);
        return request;
    }
}
