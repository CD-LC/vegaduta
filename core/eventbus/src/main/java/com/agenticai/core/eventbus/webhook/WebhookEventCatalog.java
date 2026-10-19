package com.agenticai.core.eventbus.webhook;

import com.agenticai.core.eventbus.WorkflowEventTypes;
import java.util.List;

/**
 * The event types a tenant can subscribe to. Event types are free-form (see
 * {@code WorkflowEvent}'s javadoc), so this catalog is the DOCUMENTED subset
 * the API advertises - subscriptions may also name any exact type or use
 * {@code "*"} / {@code "prefix.*"} patterns, so a new emitter never needs a
 * catalog change to be subscribable, only to be listed here for discovery.
 */
public final class WebhookEventCatalog {

    /** One catalog row: type plus a plain-language description for the console/API response. */
    public record Entry(String eventType, String description) {
    }

    public static final List<Entry> ENTRIES = List.of(
        new Entry(WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED, "Inbound WhatsApp message reached the platform"),
        new Entry(WorkflowEventTypes.CHANNEL_SMS_MESSAGE_RECEIVED, "Inbound SMS reached the platform"),
        new Entry(WorkflowEventTypes.CHANNEL_MESSENGER_MESSAGE_RECEIVED, "Inbound Facebook Messenger message"),
        new Entry(WorkflowEventTypes.CHANNEL_INSTAGRAM_MESSAGE_RECEIVED, "Inbound Instagram DM"),
        new Entry(WorkflowEventTypes.CHANNEL_SLACK_MESSAGE_RECEIVED, "Inbound Slack message"),
        new Entry(WorkflowEventTypes.CHANNEL_DISCORD_MESSAGE_RECEIVED, "Inbound Discord message"),
        new Entry(WorkflowEventTypes.CHANNEL_X_MESSAGE_RECEIVED, "Inbound X (Twitter) DM"),
        new Entry(WorkflowEventTypes.CHANNEL_TELEGRAM_MESSAGE_RECEIVED, "Inbound Telegram message"),
        new Entry(WorkflowEventTypes.CHANNEL_EMAIL_MESSAGE_RECEIVED, "Inbound email"),
        new Entry(WorkflowEventTypes.CHANNEL_TEAMS_MESSAGE_RECEIVED, "Inbound Microsoft Teams message"),
        new Entry("workflow.run.started", "A workflow run began executing"),
        new Entry("workflow.run.completed", "A workflow run finished successfully"),
        new Entry("workflow.run.failed", "A workflow run failed"),
        new Entry("workflow.run.cancelled", "A workflow run was cancelled"),
        new Entry("workflow.run.resumed", "A paused workflow run resumed"),
        new Entry("workflow.node.completed", "A single workflow node finished (carries nodeId)"),
        new Entry("workflow.output.emitted", "A workflow's OUTPUT node produced a result"),
        new Entry("approval.requested", "An action entered the human-approval queue"),
        new Entry("approval.resolved", "An approval request was approved or rejected"),
        new Entry("approval.execution.recorded", "An approved action's execution reached a terminal state"),
        new Entry("document.generation.completed", "A document-generation job finished (COMPLETE or FAILED)"),
        new Entry(WorkflowEventTypes.AGENT_MESSAGE_COMPLETED, "An agent finished and persisted a reply (payload: agentId, sessionId, answerChars, answerExcerpt, source)"),
        new Entry("webhook.test", "Synthetic event sent by the Test button / POST .../test")
    );

    private WebhookEventCatalog() {
    }
}
