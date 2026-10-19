package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.agents.tools.messaging.A2aDeliveryPort;
import com.agenticai.core.agents.tools.messaging.ChannelSendPort;
import com.agenticai.core.agents.tools.messaging.VoiceDeliveryPort;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hands a finished scheduled run's result to its destination. Two destinations only:
 *
 * <ul>
 *   <li><b>In-app</b> - a notification to the task's owner (never a tenant-wide broadcast).
 *   <li><b>A channel</b> (email / WhatsApp / Telegram / Slack) through {@link ChannelSendPort},
 *       which routes the send through the platform's {@code HumanApprovalGateService} with the
 *       channel's real {@code channel.*.send} action type. So a tenant whose approval policy
 *       requires sign-off on external messages gets exactly that for a scheduled send too - the
 *       message PARKS in the approvals inbox and is reported as {@link
 *       ScheduledTaskRun#DELIVERY_PENDING_APPROVAL}; it is never auto-approved here - and each
 *       channel adapter's own opt-in/consent rules (for example WhatsApp's service-window limits)
 *       still apply.
 * </ul>
 *
 * <p>The result is never lost: whenever the external send did not go through (no sender wired,
 * channel refused, policy wants approval) the owner gets an in-app notification instead (the full
 * text when the send FAILED, only a short "waiting for approval" note when it is merely parked,
 * since the approval card carries the text).
 *
 * <p>Best-effort by the notification convention: nothing here throws, because the run itself
 * already succeeded.
 */
@Component
public class ScheduledTaskResultDelivery {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskResultDelivery.class);
    private static final String NO_TEXT = "The agent finished this run without producing any text.";

    private final NotificationService notifications;
    private final ObjectProvider<ChannelSendPort> channelSend;
    private final ObjectProvider<A2aDeliveryPort> a2aSend;
    private final ObjectProvider<VoiceDeliveryPort> voiceSend;
    private final ScheduledTaskSettings settings;

    public ScheduledTaskResultDelivery(
            NotificationService notifications,
            ObjectProvider<ChannelSendPort> channelSend,
            ObjectProvider<A2aDeliveryPort> a2aSend,
            ObjectProvider<VoiceDeliveryPort> voiceSend,
            ScheduledTaskSettings settings) {
        this.notifications = notifications;
        this.channelSend = channelSend;
        this.a2aSend = a2aSend;
        this.voiceSend = voiceSend;
        this.settings = settings;
    }

    /**
     * @return one of the {@code ScheduledTaskRun.DELIVERY_*} constants.
     */
    public String deliver(ScheduledTask task, String resultText) {
        String body =
                clip(
                        resultText == null || resultText.isBlank() ? NO_TEXT : resultText.strip(),
                        settings.getMaxDeliveryChars());
        ScheduledTaskDelivery delivery = task.getDelivery();
        if (!delivery.isExternal() || !settings.isDeliveryAllowed(delivery)) {
            String note =
                    delivery.isExternal()
                            ? "Delivery by "
                                    + delivery.key()
                                    + " is switched off in this workspace, so the result is here instead.\n\n"
                            : null;
            inApp(task, note == null ? body : note + body, NotificationSeverity.INFO);
            return ScheduledTaskRun.DELIVERY_IN_APP;
        }
        if (delivery == ScheduledTaskDelivery.A2A) {
            return deliverA2a(task, body);
        }
        if (delivery == ScheduledTaskDelivery.VOICE) {
            return deliverVoice(task, body);
        }
        ChannelSendPort port = channelSend.getIfAvailable();
        if (port == null) {
            inApp(
                    task,
                    "Could not deliver by "
                            + delivery.key()
                            + ": sending on channels is not available. Result:\n\n"
                            + body,
                    NotificationSeverity.WARNING);
            return ScheduledTaskRun.DELIVERY_FAILED;
        }
        try {
            ChannelSendPort.ChannelSendResult result =
                    port.send(
                            delivery.channelKey(),
                            task.getDeliveryTo(),
                            subjectOf(task),
                            body,
                            requestedBy(task));
            if (result.approved()) {
                return ScheduledTaskRun.DELIVERY_DELIVERED;
            }
            inApp(
                    task,
                    "Your result is ready, but sending it by "
                            + delivery.key()
                            + " is waiting for approval (request "
                            + result.pendingRequestId()
                            + "). Open Approvals to release it.",
                    NotificationSeverity.INFO);
            return ScheduledTaskRun.DELIVERY_PENDING_APPROVAL;
        } catch (RuntimeException e) {
            log.warn(
                    "Scheduled task {} could not deliver by {}: {}",
                    task.getId(),
                    delivery.key(),
                    e.getMessage());
            inApp(
                    task,
                    "Could not deliver by "
                            + delivery.key()
                            + " ("
                            + clip(String.valueOf(e.getMessage()), 300)
                            + "). Result:\n\n"
                            + body,
                    NotificationSeverity.WARNING);
            return ScheduledTaskRun.DELIVERY_FAILED;
        }
    }

    /** A system message to the owner (a failure, an auto-pause); never carries a result. */
    public void notifyOwner(
            ScheduledTask task, String title, String body, NotificationSeverity severity) {
        try {
            notifications.create(
                    task.getOwnerSubject(),
                    NotificationCategory.AGENT,
                    clip(title, 200),
                    clip(body, 4000),
                    severity,
                    "/scheduled",
                    null);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not record a notification for scheduled task {} (best-effort): {}",
                    task.getId(),
                    e.getMessage());
        }
    }

    /**
     * A2A delivery: hands the result to the remote agent named by {@code deliveryTo} (a signed
     * agent-card URL) through {@link A2aDeliveryPort} - the same allow-list + SSRF + card-verification
     * path the {@code a2a_call_agent} tool uses. There is no approval-park path here; the port either
     * delivered the text or returned why it could not, and either way the owner hears about it in-app.
     */
    private String deliverA2a(ScheduledTask task, String body) {
        A2aDeliveryPort port = a2aSend.getIfAvailable();
        if (port == null) {
            inApp(task, "Could not deliver by a2a: the outbound A2A client is not available. Result:\n\n" + body,
                    NotificationSeverity.WARNING);
            return ScheduledTaskRun.DELIVERY_FAILED;
        }
        try {
            A2aDeliveryPort.A2aSendResult result = port.send(task.getDeliveryTo(), body, requestedBy(task));
            if (result.delivered()) {
                return ScheduledTaskRun.DELIVERY_DELIVERED;
            }
            inApp(task,
                    "The remote agent did not accept the result (" + clip(result.detail(), 300) + "). Result:\n\n" + body,
                    NotificationSeverity.WARNING);
            return ScheduledTaskRun.DELIVERY_FAILED;
        } catch (RuntimeException e) {
            log.warn("Scheduled task {} could not deliver by a2a: {}", task.getId(), e.getMessage());
            inApp(task,
                    "Could not deliver by a2a (" + clip(String.valueOf(e.getMessage()), 300) + "). Result:\n\n" + body,
                    NotificationSeverity.WARNING);
            return ScheduledTaskRun.DELIVERY_FAILED;
        }
    }

    /**
     * Voice delivery: phones {@code deliveryTo} and speaks the result through {@link
     * VoiceDeliveryPort} (the tenant's voice credential, consent rules and provider apply inside).
     * Spoken text is trimmed harder than the channel limit - nobody listens to a 3,500-char call.
     */
    private String deliverVoice(ScheduledTask task, String body) {
        VoiceDeliveryPort port = voiceSend.getIfAvailable();
        if (port == null) {
            inApp(task, "Could not deliver by voice: outbound calls are not configured. Result:\n\n" + body,
                    NotificationSeverity.WARNING);
            return ScheduledTaskRun.DELIVERY_FAILED;
        }
        try {
            port.call(task.getDeliveryTo(), clip(body, 900), requestedBy(task));
            return ScheduledTaskRun.DELIVERY_DELIVERED;
        } catch (RuntimeException e) {
            log.warn("Scheduled task {} could not deliver by voice: {}", task.getId(), e.getMessage());
            inApp(task,
                    "Could not deliver by voice (" + clip(String.valueOf(e.getMessage()), 300) + "). Result:\n\n" + body,
                    NotificationSeverity.WARNING);
            return ScheduledTaskRun.DELIVERY_FAILED;
        }
    }

    private void inApp(ScheduledTask task, String body, NotificationSeverity severity) {
        notifyOwner(task, "Scheduled task: " + task.getName(), body, severity);
    }

    static String requestedBy(ScheduledTask task) {
        return "agent:" + task.getAgentId() + ":scheduled-task:" + task.getId();
    }

    private static String subjectOf(ScheduledTask task) {
        return "Scheduled task: " + task.getName();
    }

    private static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
