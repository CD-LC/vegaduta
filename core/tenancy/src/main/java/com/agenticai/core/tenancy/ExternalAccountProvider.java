package com.agenticai.core.tenancy;

/**
 * The external system an inbound-webhook-carried account identifier belongs
 * to - currently Twilio (the Account SID on every inbound message webhook),
 * WORKFLOW_WEBHOOK (a per-workflow secret token minted by {@code
 * WorkflowService#enableWebhook} - see {@code WorkflowWebhookController} for
 * the same "resolve tenant before anything else" pattern Twilio's webhook
 * uses), DEVICE (Mobile SDK, #157: a paired phone's device token, linked at
 * claim time by {@code DevicePairingService#claim} and resolved on every
 * later poll/result call by {@code DeviceAuthResolver}), and META_WHATSAPP
 * (a tenant's own direct Meta WhatsApp Business Platform connection, keyed
 * by Phone Number ID - see {@code MetaWhatsAppCredentialService#activate}
 * and {@code MetaWhatsAppWebhookController}). Extended the same way once Telegram (Omnichannel Build
 * Plan Stage 3) needs the same resolution for inbound (a bot token or bot
 * id, in that case) - outbound-only Telegram already works today via a
 * single platform-wide bot token, so no link is needed for that direction
 * yet.
 */
public enum ExternalAccountProvider {
    TWILIO,
    WORKFLOW_WEBHOOK,
    DEVICE,
    META_WHATSAPP,
    // Social channels (Instagram/Messenger) - both keyed off the tenant's
    // connected Facebook Page (see MetaSocialCredentialService): Messenger
    // resolves by the Page ID itself, Instagram by the linked Instagram
    // Business Account ID, since Meta's webhook payload uses whichever one
    // is the actual message recipient depending on which surface ("page" vs
    // "instagram" object) the event came in on.
    META_MESSENGER,
    META_INSTAGRAM,
    // SLACK: a tenant's own Slack App (Bot Token + Signing Secret), keyed by
    // the workspace's Team ID present in every Events API payload - resolved
    // the same "unverified-but-self-defeating-to-forge identifier, then
    // verify with that tenant's own secret" way Twilio/Meta's webhooks are.
    SLACK,
    // DISCORD: a tenant's own Discord Application/Bot, keyed by Application
    // ID. Unlike every provider above, Discord's inbound path has no public
    // webhook at all for ordinary messages (Discord requires a persistent
    // Gateway WebSocket connection to receive them) - inbound instead comes
    // from the platform's own `channels/discord` Node microservice, which
    // authenticates to this platform with a shared internal secret (same
    // "internal service, not an unknown third party" trust model) and forwards the Application ID it
    // read off its own Gateway session so this lookup still works unchanged.
    DISCORD,
    // TELEGRAM: a tenant's own Telegram Bot, keyed by the bot's numeric user
    // id (from getMe). Unlike Discord, Telegram delivers ordinary messages
    // via a real public webhook (TelegramWebhookController), so this lookup
    // resolves the tenant straight from the URL path, then verifies with a
    // per-tenant secret_token - the same "public but self-defeating to
    // forge, then verify" pattern Slack/Twilio's webhooks use.
    TELEGRAM,
    // MCP_SERVER_KEY: core/mcpserver's inbound MCP server surface - a
    // tenant-admin-generated API key an external MCP client (Claude Desktop,
    // another agent framework, a customer's own tooling) presents to call
    // this tenant's own opt-in Agents. Keyed by the SHA-256 hash of the raw
    // key (see McpApiKeyHasher), never the raw key itself - unlike every
    // provider above, this externalAccountId is itself derived from a
    // platform-generated secret, not a third-party-assigned identifier, so
    // hashing it here costs nothing and avoids a plaintext-bearer-secret
    // row in this cross-tenant control_plane table.
    MCP_SERVER_KEY,
    // AGENT_SHARE: a registered tenant's own agent, shared to anonymous
    // external viewers - see core/agents' AgentShareService/
    // AgentShareController. Keyed by the platform-generated, unguessable
    // share id itself (there is no third-party identity behind this at all,
    // same reasoning as MCP_SERVER_KEY above, except the raw id is stored
    // rather than a hash - unlike an API key, a share id grants no write
    // access and is deliberately meant to be pasted into a URL and shared
    // around, so hashing it here would only cost lookup convenience for no
    // real confidentiality benefit).
    AGENT_SHARE,
    // WORKFLOW_SHARE: the same anonymous-public-link pattern as AGENT_SHARE
    // above, for a registered tenant's own workflow - see core/workflow's
    // WorkflowShareService/WorkflowShareController. Same keying rationale
    // (raw, unguessable share id stored directly, not hashed).
    WORKFLOW_SHARE,
    // TEAMS: a tenant's own Azure Bot registration (Microsoft Teams channel,
    // migration V240), keyed by the bot's Microsoft App ID - the path
    // parameter on /webhook/teams/{appId}. Tenant resolution happens BEFORE
    // the Bot Framework JWT is checked because the token's audience claim IS
    // the appId itself: an attacker who knew another tenant's appId could
    // mint nothing without that bot's app secret, and the signature+issuer
    // check in TeamsWebhookController is what actually authenticates the call
    // - the same "self-defeating to forge, then verify" pattern SLACK and
    // TELEGRAM above use.
    TEAMS,
    // STUDIO_APP: a Phone-App-Studio app the tenant published (migration
    // V359, com.agenticai.core.common.studioapp), keyed by the app's public
    // slug on /api/public/apps/{slug}. Same keying rationale as AGENT_SHARE:
    // the slug is a platform-generated unguessable id meant to live in a
    // shared URL, and the anonymous endpoints behind it are rate-limited and
    // can only invoke what the owner wired in.
    STUDIO_APP,
    // STUDIO_APP_DOMAIN: a VERIFIED custom hostname of a published studio app
    // (migration V371), keyed by the lowercase FQDN. Doubles as the global
    // hostname-uniqueness registry: only one tenant can hold a hostname.
    STUDIO_APP_DOMAIN,
    // TENANT_API_KEY: a tenant-admin-issued REST API key (V483,
    // com.agenticai.core.tenancy.apikey), keyed by the SHA-256 hash of the
    // vdak_ bearer - same "hash here, never the plaintext" posture as
    // MCP_SERVER_KEY, since this control_plane table holds only the lookup
    // and the key row itself lives in the tenant schema.
    TENANT_API_KEY,
    // SIGNAL: a tenant's registered Signal number (signal-cli REST API, V600); inbound is pulled, so this only restores pollers after a restart.
    SIGNAL,
    // MATRIX: a tenant's own Matrix bot user, keyed by its MXID ("@bot:example.org"). Matrix has no
    // inbound webhook: the link only tells the sync loop which tenant schema owns the bot.
    MATRIX,
    // IMESSAGE: a tenant's BlueBubbles connection (V610), keyed by the random public webhook id in
    // /webhook/imessage/{webhookId}; the call is then authenticated by a per-tenant secret token.
    IMESSAGE,
    // WHATSAPP_QR: a tenant's linked-device WhatsApp session on the whatsapp-qr bridge, keyed by
    // the random session key (whatsapp_qr_connection.session_key). Inbound is pulled by a
    // per-session receiver, so this link only restores receivers after a restart, same as SIGNAL.
    WHATSAPP_QR
}
