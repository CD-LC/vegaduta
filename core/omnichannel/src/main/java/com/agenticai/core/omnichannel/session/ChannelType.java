package com.agenticai.core.omnichannel.session;

/**
 * Which messaging channel a {@link ChannelSession} belongs to. Originally
 * only WHATSAPP - MESSENGER and INSTAGRAM
 * are the first channels added since, both landing as "add a case" the same
 * way {@link com.agenticai.core.approval.ApprovalActionExecutor} already
 * treats new gated action types: every piece of shared inbound
 * infrastructure this enum feeds - {@link ChannelSessionService}'s
 * resolve/pairing-gate logic, {@link ChannelDefaultAgent}, {@link
 * com.agenticai.core.omnichannel.session.ChannelPairingController}'s pending-
 * pairings inbox - is already fully channel-agnostic, so neither of those
 * classes needed a single line changed to support the two new values.
 * Telegram (TELEGRAM) and Email/IMAP (EMAIL) joined the same way - both now
 * have real inbound routing (see {@code TelegramWebhookController} and
 * {@code EmailInboundPollingJob}), so this enum no longer has an
 * outbound-only exception.
 *
 * <p><b>VOICE (Master Roadmap 2026 item #17):</b> inbound phone calls over
 * Twilio Conversation Relay - see {@code TwilioVoiceWebhookController} and
 * {@code VoiceRelayWebSocketHandler}. Reuses this same pairing/default-agent
 * machinery unchanged, exactly as every other channel added since WHATSAPP
 * has - the one real difference is {@code externalId} for a VOICE session is
 * the caller's phone number (so the same person's memory persists call to
 * call, same reasoning as WhatsApp's phone-number-keyed sessions), and there
 * is no separate "send" controller: a voice reply is spoken live over the
 * WebSocket bridge, not queued through {@link
 * com.agenticai.core.approval.HumanApprovalGateService} like every other
 * channel's outbound text - see that handler's javadoc for why a live phone
 * call can't wait on human approval mid-conversation.
 */
public enum ChannelType {
    WHATSAPP,
    MESSENGER,
    INSTAGRAM,
    SLACK,
    DISCORD,
    X,
    TELEGRAM,
    EMAIL,
    // Microsoft Teams (2026-09-23, migration V240) - a tenant's own Azure Bot
    // registration reached through the Bot Framework: inbound activities arrive
    // as JWT-signed POSTs at /webhook/teams/{appId} (TeamsWebhookController),
    // replies go through TeamsApiClient's connector call. external_id is the
    // Bot Framework conversation id; the companion service_url needed to reply
    // lives in teams_conversation_route (it is per-conversation, not per-bot).
    TEAMS,
    VOICE,
    // Plain SMS (2026-10-14). Until now an inbound SMS could only fire a
    // workflow (TwilioSmsWebhookController's original scope, #422); it is now
    // also a chat surface, reached by a feature phone with no data plan. Same
    // pairing/default-agent machinery as every other channel; external_id is
    // the sender's phone number (as for WHATSAPP and VOICE, so memory follows
    // the person). Replies go through the approval gate as
    // TwilioSmsChannelAdapter.ACTION_TYPE ("channel.sms.send") and are cut to
    // app.omnichannel.sms.max-reply-chars - a carrier concatenates at most
    // ~10 segments, and every segment is paid for by the operator.
    SMS,
    // Signal (2026-10-17, V600) via an operator-run signal-cli REST API; see the signal package.
    SIGNAL,
    // Matrix (personal-agent tranche): a bot user on a homeserver the operator chooses, reached
    // by the Client-Server API sync loop. external_id is the room id (DM or group room).
    MATRIX,
    // iMessage via the operator's own BlueBubbles server on a Mac (V610); see omnichannel-social imessage.
    IMESSAGE,
    // WhatsApp QR: the tenant's OWN number linked as a companion device on the operator's
    // whatsapp-qr bridge (services/whatsapp-qr, Baileys). The only WhatsApp path that works on a
    // personal install - the bridge dials out, so no public webhook exists. external_id is the
    // sender's digits or a "<digits>@lid" privacy id; see the whatsappqr package. Distinct from
    // WHATSAPP (the Meta/Twilio Business API channel) so pairing and routing stay separate.
    WHATSAPP_QR
}
