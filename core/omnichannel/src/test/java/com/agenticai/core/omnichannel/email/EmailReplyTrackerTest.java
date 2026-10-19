package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.omnichannel.email.EmailReplyClassifier.Headers;
import com.agenticai.core.omnichannel.email.EmailReplyClassifier.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmailReplyTrackerTest {

    private EmailMessageRepository messages;
    private EmailSuppressionRepository suppressions;
    private WorkflowEventPublisher publisher;
    private EmailReplyTracker tracker;
    private EmailMessage original;

    @BeforeEach
    void setUp() {
        messages = mock(EmailMessageRepository.class);
        suppressions = mock(EmailSuppressionRepository.class);
        publisher = mock(WorkflowEventPublisher.class);
        tracker = new EmailReplyTracker(messages, suppressions, publisher);
        original = new EmailMessage("run-9", "node-3", "lead@x.test", "Hi", "orig@core.test", "tk",
            EmailMessage.STATUS_SENT, null, Instant.now());
        when(messages.findByMessageIdIn(any())).thenReturn(List.of(original));
    }

    private static Headers headers(String contentType, String subject, String from) {
        return new Headers("<orig@core.test>", "<orig@core.test>", null, null, null, contentType, subject, from, null);
    }

    @Test
    void humanReplyStampsRepliedAtAndPublishesReplyEventWithOriginalRunNodeRecipient() {
        EmailReplyTracker.Outcome outcome = tracker.track(headers("text/plain", "Re: Hi", "lead@x.test"), "lead@x.test", "Sounds good");

        assertEquals(Kind.REPLY, outcome.kind());
        assertEquals(1, outcome.matched());
        assertNotNull(original.getRepliedAt());
        assertEquals("REPLY", original.getReplyKind());
        verify(messages).save(original);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publish(eq(WorkflowEventTypes.EMAIL_REPLY_RECEIVED), eq("run-9"), payload.capture());
        assertEquals("node-3", payload.getValue().get("nodeId"));
        assertEquals("lead@x.test", payload.getValue().get("recipient"));
        assertEquals("Sounds good", payload.getValue().get("snippet"));
        verify(suppressions, never()).save(any());
    }

    @Test
    void permanentBounceSuppressesRecipientAndPublishesBounceEvent() {
        when(suppressions.existsByEmail("lead@x.test")).thenReturn(false);
        tracker.track(headers("multipart/report; report-type=delivery-status", "Undeliverable: Hi", "mailer-daemon@mx.test"),
            "mailer-daemon@mx.test", "Status: 5.1.1 user unknown");

        assertEquals("BOUNCE", original.getReplyKind());
        ArgumentCaptor<EmailSuppression> saved = ArgumentCaptor.forClass(EmailSuppression.class);
        verify(suppressions).save(saved.capture());
        assertEquals("lead@x.test", saved.getValue().getEmail());
        assertEquals("BOUNCE", saved.getValue().getReason());
        verify(publisher).publish(eq(WorkflowEventTypes.EMAIL_BOUNCED), eq("run-9"), any());
    }

    @Test
    void transientBounceIsRecordedButNotSuppressed() {
        tracker.track(headers("multipart/report; report-type=delivery-status", "Undeliverable", "mailer-daemon@mx.test"),
            "mailer-daemon@mx.test", "Status: 4.2.2 mailbox full");
        assertEquals("BOUNCE", original.getReplyKind());
        verify(suppressions, never()).save(any());
    }

    @Test
    void autoReplyPublishesItsOwnEventType() {
        tracker.track(new Headers("<orig@core.test>", null, "auto-replied", null, null, "text/plain", "Automatic reply: Hi", "lead@x.test", null),
            "lead@x.test", "I am away");
        assertEquals("AUTO_REPLY", original.getReplyKind());
        verify(publisher).publish(eq(WorkflowEventTypes.EMAIL_AUTO_REPLY_RECEIVED), anyString(), any());
    }

    @Test
    void laterHumanReplyUpgradesAnEarlierAutoReplyButNotTheOtherWayRound() {
        original.markReplied(Instant.now(), "AUTO_REPLY");
        EmailReplyTracker.Outcome up = tracker.track(headers("text/plain", "Re: Hi", "lead@x.test"), "lead@x.test", "real answer");
        assertEquals(1, up.matched());
        assertEquals("REPLY", original.getReplyKind());

        EmailReplyTracker.Outcome down = tracker.track(
            new Headers("<orig@core.test>", null, "auto-replied", null, null, "text/plain", "Auto: x", "lead@x.test", null),
            "lead@x.test", "away");
        assertEquals(0, down.matched());
        assertEquals("REPLY", original.getReplyKind());
    }

    @Test
    void mailWithNoReferencesOrNoMatchIsIgnored() {
        assertEquals(0, tracker.track(new Headers(null, null, null, null, null, "text/plain", "Hello", "a@x.test", null), "a@x.test", "hi").matched());
        when(messages.findByMessageIdIn(any())).thenReturn(List.of());
        assertEquals(0, tracker.track(headers("text/plain", "Re: x", "a@x.test"), "a@x.test", "hi").matched());
        assertNull(original.getRepliedAt());
        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void repositoryFailureNeverBreaksInboundProcessing() {
        when(messages.findByMessageIdIn(any())).thenThrow(new RuntimeException("relation email_message does not exist"));
        EmailReplyTracker.Outcome outcome = tracker.track(headers("text/plain", "Re: x", "a@x.test"), "a@x.test", "hi");
        assertEquals(0, outcome.matched());
        assertTrue(outcome.kind() == Kind.REPLY);
    }
}
