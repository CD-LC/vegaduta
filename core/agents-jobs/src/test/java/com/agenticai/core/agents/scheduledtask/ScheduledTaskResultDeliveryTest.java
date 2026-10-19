package com.agenticai.core.agents.scheduledtask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Kind;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskSchedule.Spec;
import com.agenticai.core.agents.tools.messaging.ChannelSendPort;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Result delivery: in-app to the owner, or through the channel send port (which is the platform's
 * approval gate) - never auto-approving, never losing the result.
 */
class ScheduledTaskResultDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    private NotificationService notifications;
    private ChannelSendPort port;
    private ObjectProvider<ChannelSendPort> provider;
    private ObjectProvider<com.agenticai.core.agents.tools.messaging.A2aDeliveryPort> a2aProvider;
    private ObjectProvider<com.agenticai.core.agents.tools.messaging.VoiceDeliveryPort> voiceProvider;
    private ScheduledTaskResultDelivery delivery;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        notifications = mock(NotificationService.class);
        port = mock(ChannelSendPort.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);
        a2aProvider = mock(ObjectProvider.class);
        voiceProvider = mock(ObjectProvider.class);
        delivery =
                new ScheduledTaskResultDelivery(
                        notifications, provider, a2aProvider, voiceProvider, settings("in_app,email,whatsapp"));
    }

    private static ScheduledTaskSettings settings(String channels) {
        return new ScheduledTaskSettings(true, true, 10, 3, 15, 3, 2000, 720, true, 300, channels);
    }

    private static ScheduledTask task(ScheduledTaskDelivery d, String to) {
        ScheduledTaskSchedule.Compiled schedule =
                ScheduledTaskSchedule.compile(
                        new Spec(Kind.DAILY, "08:00", null, null), "UTC", 15, NOW);
        return ScheduledTask.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Digest",
                "p",
                schedule,
                d,
                to,
                null,
                null,
                "user-1",
                NOW,
                NOW);
    }

    private String lastNotificationBody(NotificationSeverity severity) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notifications)
                .create(
                        eq("user-1"),
                        eq(NotificationCategory.AGENT),
                        eq("Scheduled task: Digest"),
                        body.capture(),
                        eq(severity),
                        eq("/scheduled"),
                        isNull());
        return body.getValue();
    }

    @Test
    void inAppDeliveryNotifiesTheOwnerAndNeverTouchesAChannel() {
        String status = delivery.deliver(task(ScheduledTaskDelivery.IN_APP, null), "Top 5 emails");

        assertThat(status).isEqualTo(ScheduledTaskRun.DELIVERY_IN_APP);
        assertThat(lastNotificationBody(NotificationSeverity.INFO)).isEqualTo("Top 5 emails");
        verifyNoInteractions(port);
    }

    @Test
    void aChannelSendAcceptedByTheGateIsDeliveredAndNothingElseIsSent() {
        ScheduledTask t = task(ScheduledTaskDelivery.WHATSAPP, "+919876543210");
        when(port.send(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(
                        new ChannelSendPort.ChannelSendResult(true, null, "Send WhatsApp message"));

        String status = delivery.deliver(t, "Top 5 emails");

        assertThat(status).isEqualTo(ScheduledTaskRun.DELIVERY_DELIVERED);
        verify(port)
                .send(
                        "whatsapp",
                        "+919876543210",
                        "Scheduled task: Digest",
                        "Top 5 emails",
                        "agent:" + t.getAgentId() + ":scheduled-task:" + t.getId());
        verifyNoInteractions(notifications);
    }

    @Test
    void aSendTheGateParksIsReportedAsPendingApprovalAndNeverForced() {
        ScheduledTask t = task(ScheduledTaskDelivery.WHATSAPP, "+919876543210");
        when(port.send(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(
                        new ChannelSendPort.ChannelSendResult(
                                false, "req-42", "Send WhatsApp message"));

        String status = delivery.deliver(t, "Top 5 emails: secret details");

        assertThat(status).isEqualTo(ScheduledTaskRun.DELIVERY_PENDING_APPROVAL);
        String body = lastNotificationBody(NotificationSeverity.INFO);
        assertThat(body).contains("waiting for approval").contains("req-42");
        // The parked text lives on the approval card; the notification must not leak it a second
        // time.
        assertThat(body).doesNotContain("secret details");
    }

    @Test
    void aFailedSendFallsBackToAnInAppCopySoTheResultIsNeverLost() {
        ScheduledTask t = task(ScheduledTaskDelivery.EMAIL, "me@example.com");
        when(port.send(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("no email channel connected"));

        String status = delivery.deliver(t, "Top 5 emails");

        assertThat(status).isEqualTo(ScheduledTaskRun.DELIVERY_FAILED);
        String body = lastNotificationBody(NotificationSeverity.WARNING);
        assertThat(body)
                .contains("Could not deliver by email")
                .contains("no email channel connected")
                .contains("Top 5 emails");
    }

    @Test
    void withNoChannelSenderWiredTheResultStillReachesTheOwnerInApp() {
        when(provider.getIfAvailable()).thenReturn(null);

        String status =
                delivery.deliver(
                        task(ScheduledTaskDelivery.WHATSAPP, "+919876543210"), "Top 5 emails");

        assertThat(status).isEqualTo(ScheduledTaskRun.DELIVERY_FAILED);
        assertThat(lastNotificationBody(NotificationSeverity.WARNING))
                .contains("not available")
                .contains("Top 5 emails");
    }

    @Test
    void aChannelSwitchedOffByConfigDeliversInAppWithANote() {
        delivery = new ScheduledTaskResultDelivery(notifications, provider, a2aProvider, voiceProvider, settings("in_app"));

        String status =
                delivery.deliver(
                        task(ScheduledTaskDelivery.EMAIL, "me@example.com"), "Top 5 emails");

        assertThat(status).isEqualTo(ScheduledTaskRun.DELIVERY_IN_APP);
        assertThat(lastNotificationBody(NotificationSeverity.INFO))
                .contains("switched off")
                .contains("Top 5 emails");
        verify(port, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    void anEmptyResultIsSaidOutLoud() {
        delivery.deliver(task(ScheduledTaskDelivery.IN_APP, null), "   ");
        assertThat(lastNotificationBody(NotificationSeverity.INFO))
                .contains("without producing any text");
    }

    @Test
    void aLongResultIsClippedToTheConfiguredLength() {
        ScheduledTask t = task(ScheduledTaskDelivery.WHATSAPP, "+919876543210");
        when(port.send(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ChannelSendPort.ChannelSendResult(true, null, "ok"));

        delivery.deliver(t, "x".repeat(5000));

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(port).send(anyString(), anyString(), anyString(), sent.capture(), anyString());
        assertThat(sent.getValue()).hasSize(300).endsWith("…");
    }

    @Test
    void ownerNotificationsNeverBroadcastToTenantAdmins() {
        delivery.notifyOwner(
                task(ScheduledTaskDelivery.IN_APP, null),
                "Scheduled task paused: Digest",
                "why",
                NotificationSeverity.ERROR);

        ArgumentCaptor<String> recipient = ArgumentCaptor.forClass(String.class);
        verify(notifications)
                .create(
                        recipient.capture(),
                        eq(NotificationCategory.AGENT),
                        anyString(),
                        anyString(),
                        eq(NotificationSeverity.ERROR),
                        eq("/scheduled"),
                        isNull());
        assertThat(recipient.getValue()).isEqualTo("user-1");
    }
}
