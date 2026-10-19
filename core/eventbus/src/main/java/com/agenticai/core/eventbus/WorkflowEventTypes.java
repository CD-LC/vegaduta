package com.agenticai.core.eventbus;

/**
 * Event type string constants for {@link WorkflowEvent#eventType()}. A plain
 * constants class, not an enum - see {@link WorkflowEvent}'s javadoc for why
 * this stays free-form.
 */
public final class WorkflowEventTypes {

    /**
     * Phase 0's proof-of-pipe event: published (behind {@code
     * app.workflow-events.enabled}) when a paired WhatsApp sender's message
     * reaches {@code TwilioWebhookController}, before the existing
     * synchronous agent-chat handling runs. Carries no business meaning yet
     * - nothing in the platform consumes this to actually do anything; it
     * exists so {@link WorkflowEventOutboxRelay} and {@code
     * WorkflowEventLogger} have a real event to move end-to-end through
     * Postgres -> Kafka -> a consumer, which is Phase 0's entire Definition
     * of Done per the Interactive Workflow Designer PRD.
     */
    public static final String CHANNEL_WHATSAPP_MESSAGE_RECEIVED = "channel.whatsapp.message.received";

    /** Same "proof-of-pipe, no consumer logic yet" role as the WhatsApp constant above, for Facebook Messenger (see {@code MetaSocialWebhookController}). */
    public static final String CHANNEL_MESSENGER_MESSAGE_RECEIVED = "channel.messenger.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for Instagram DMs. */
    public static final String CHANNEL_INSTAGRAM_MESSAGE_RECEIVED = "channel.instagram.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for Slack. */
    public static final String CHANNEL_SLACK_MESSAGE_RECEIVED = "channel.slack.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for Discord. */
    public static final String CHANNEL_DISCORD_MESSAGE_RECEIVED = "channel.discord.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for X (Twitter) DMs - published from {@code XDmPollingJob}, not a webhook. */
    public static final String CHANNEL_X_MESSAGE_RECEIVED = "channel.x.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for Telegram - published from {@code TelegramWebhookController}. */
    public static final String CHANNEL_TELEGRAM_MESSAGE_RECEIVED = "channel.telegram.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for Email/IMAP - published from {@code EmailInboundPollingJob}, not a webhook. */
    public static final String CHANNEL_EMAIL_MESSAGE_RECEIVED = "channel.email.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for Microsoft Teams - published from {@code TeamsWebhookController}. */
    public static final String CHANNEL_TEAMS_MESSAGE_RECEIVED = "channel.teams.message.received";

    /** Same role as {@link #CHANNEL_MESSENGER_MESSAGE_RECEIVED}, for plain SMS - published from {@code TwilioSmsWebhookController}. */
    public static final String CHANNEL_SMS_MESSAGE_RECEIVED = "channel.sms.message.received";

    /**
     * Published by {@code core.documentgen}'s {@code
     * DocumentGenerationEventListener} once a document-generation job
     * reaches a terminal state ({@code COMPLETE} or {@code FAILED}) - the
     * payload carries {@code jobId}/{@code tenantId}/{@code success}, same
     * shape as {@code DocumentGenerationCompletedEvent}. Like the channel
     * constants above, no consumer reacts to this yet; it exists as the
     * trigger seam a future "on document generated" workflow node can hang
     * off later. The publish call is wrapped in try/catch by its caller (see
     * that class's javadoc) so a disabled/misconfigured eventbus can never
     * fail the underlying document generation job.
     */
    public static final String DOCUMENT_GENERATION_COMPLETED = "document.generation.completed";

    /**
     * Tenant webhooks (V241) - published by {@code AgentChatService} once an
     * assistant reply has been persisted to session memory (the "the agent
     * answered" moment), on every depth's path: cache hits, streamed (FAST)
     * completions and buffered (CAREFUL/RIGOROUS/PLANNED) answers alike.
     * Payload: {@code agentId}, {@code sessionId}, {@code answerChars},
     * {@code answerExcerpt} (truncated), {@code source} (e.g. {@code live},
     * {@code cache}, {@code semantic-cache}). runId is the sessionId, so a
     * consumer can group every message of one conversation. Emitted with the
     * tenant id captured on the request thread via {@link
     * WorkflowEventPublisher#publish(String, String, Map, String)} - the
     * streaming completion runs on a worker thread where {@code
     * TenantContext} is already empty.
     */
    public static final String AGENT_MESSAGE_COMPLETED = "agent.message.completed";

    /**
     * Published by {@code EmailReplyTracker} when an inbound email's {@code
     * In-Reply-To}/{@code References} matches a message sent by a {@code
     * bulk_email} workflow node and the mail is a human reply. Payload:
     * {@code runId}/{@code nodeId}/{@code recipient}/{@code messageId} of the
     * ORIGINAL send, {@code fromAddress}, {@code subject}, {@code snippet}.
     * The recipient email is the join key for a future leads/CRM lookup.
     */
    public static final String EMAIL_REPLY_RECEIVED = "email.reply_received";

    /** Same payload as {@link #EMAIL_REPLY_RECEIVED}, for a delivery-failure (DSN/MAILER-DAEMON) matched to a bulk send; adds {@code permanent}. */
    public static final String EMAIL_BOUNCED = "email.bounced";

    /** Same payload as {@link #EMAIL_REPLY_RECEIVED}, for an out-of-office/auto-responder reply matched to a bulk send. */
    public static final String EMAIL_AUTO_REPLY_RECEIVED = "email.auto_reply_received";

    /**
     * C1 versioning - published by {@code AgentVersionService} /
     * {@code WorkflowVersionService} on every STAGING/PRODUCTION label move
     * (manual promote AND auto-assign on save in compatibility mode).
     * Payload: {@code entityType} (AGENT/WORKFLOW), {@code entityId},
     * {@code versionId}, {@code versionNumber}, {@code label},
     * {@code automatic}. The run/aggregate id is the entity id. Consumers:
     * the version resolution caches evict on it (the in-JVM
     * {@link WorkflowEventSignal} listener covers the publishing node; the
     * shared Redis delete covers the rest).
     */
    public static final String VERSION_PROMOTED = "version.promoted";

    /** Same payload as {@link #VERSION_PROMOTED}, for {@code rollback(entityId)} label moves. */
    public static final String VERSION_ROLLED_BACK = "version.rolled_back";

    private WorkflowEventTypes() {
    }
}
