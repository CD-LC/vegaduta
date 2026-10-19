import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { AuthorizeUrlResponse, ChannelsStatusResponse, ContactGroup, DtmfMapping } from "./types";
import { getPublicEnv } from "../env";
import { pagedQuery, toPaged } from "./paged";

// ok=false carries code/message/reasons (a flat shape: the web tsconfig is not strict, so a
// discriminated union would not narrow).
export type WhatsAppBindResult = {
  ok: boolean;
  code?: "WHATSAPP_TASK_REQUIRED" | "WHATSAPP_OPEN_ENDED_AGENT";
  message?: string;
  reasons?: string[];
};

export type WhatsAppQuality = { available: boolean; qualityRating: string | null; status: string | null };

// SEC-018: core binds each OAuth state to the browser that started the flow
// with an HttpOnly cookie set on THIS response. A cross-origin fetch only
// stores that cookie when it sends credentials, and the browser then also
// needs core's CORS to allow credentials for this origin - so it is gated on
// NEXT_PUBLIC_OAUTH_STATE_COOKIE=1 and switched on together with core's
// app.oauth.state.require-browser-cookie. skipGetCache: every authorize URL
// carries a fresh single-use state, so it must never be served from the GET
// dedupe/SWR cache.
function oauthAuthorizeUrl(path: string): Promise<AuthorizeUrlResponse> {
  const withCookie = getPublicEnv("NEXT_PUBLIC_OAUTH_STATE_COOKIE") === "1";
  return apiFetch(path, { skipGetCache: true, ...(withCookie ? { credentials: "include" as const } : {}) }).then(
    toJson<AuthorizeUrlResponse>
  );
}

export const channelsApi = {
  // Twilio WhatsApp credential (Omnichannel Stage 2) - POST is open to any
  // authenticated tenant member server-side; GET returns 204/null before any
  // credential has been activated (see TwilioCredentialController#current).
  getTwilioCredential: () =>
    apiFetch(`/api/channels/twilio/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateTwilioCredential: (payload: object) =>
    apiFetch(`/api/channels/twilio/credential`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),
  // Marks the credential DISCONNECTED; WhatsAppDispatchService then uses
  // Meta (if connected) for every future outbound send.
  disconnectTwilioCredential: () =>
    apiFetch(`/api/channels/twilio/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),

  // Direct Meta WhatsApp Cloud API credential (WhatsAppDispatchService's
  // alternative to Twilio) - same shape/posture as the Twilio pair above.
  getMetaWhatsAppCredential: () =>
    apiFetch(`/api/channels/meta-whatsapp/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateMetaWhatsAppCredential: (payload: object) =>
    apiFetch(`/api/channels/meta-whatsapp/credential`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),
  // "Log out of WhatsApp (Meta)" - marks the credential DISCONNECTED and
  // drops the phoneNumberId->tenant link, so inbound webhook payloads stop
  // resolving this tenant entirely (not just failing signature checks).
  disconnectMetaWhatsAppCredential: () =>
    apiFetch(`/api/channels/meta-whatsapp/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),

  // Social channels (Messenger + Instagram) - one Facebook Page connection
  // serves both; instagramBusinessAccountId in the activation payload is
  // optional (Messenger-only tenants leave it blank). See
  // MetaSocialCredentialService's javadoc for exactly where these values
  // come from.
  getMetaSocialCredential: () =>
    apiFetch(`/api/channels/meta-social/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateMetaSocialCredential: (payload: object) =>
    apiFetch(`/api/channels/meta-social/credential`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),
  // "Log out of this Facebook Page" - disconnects BOTH Messenger and
  // Instagram (they share this one credential) and drops both tenant links.
  disconnectMetaSocialCredential: () =>
    apiFetch(`/api/channels/meta-social/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendMessengerMessage: (payload: object) =>
    apiFetch(`/api/channels/social/messenger/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  sendInstagramMessage: (payload: object) =>
    apiFetch(`/api/channels/social/instagram/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setMessengerDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/social/messenger/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),
  setInstagramDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/social/instagram/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Slack - a tenant's own Slack App (Bot Token + Signing Secret).
  getSlackCredential: () =>
    apiFetch(`/api/channels/slack/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateSlackCredential: (payload: object) =>
    apiFetch(`/api/channels/slack/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  disconnectSlackCredential: () =>
    apiFetch(`/api/channels/slack/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendSlackMessage: (payload: object) =>
    apiFetch(`/api/channels/slack/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setSlackDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/slack/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Discord - a tenant's own Discord Application/Bot. Inbound messages
  // require the platform's own channels/discord Node Gateway microservice
  // (see its README) rather than a public webhook Discord calls directly.
  getDiscordCredential: () =>
    apiFetch(`/api/channels/discord/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateDiscordCredential: (payload: object) =>
    apiFetch(`/api/channels/discord/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  disconnectDiscordCredential: () =>
    apiFetch(`/api/channels/discord/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendDiscordMessage: (payload: object) =>
    apiFetch(`/api/channels/discord/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setDiscordDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/discord/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Embedded Signup - the guided, one-click alternative to the manual form
  // above (see MetaEmbeddedSignupService's javadoc). getEmbeddedSignupConfig
  // throws (400) if the platform operator hasn't set this up yet -
  // ChannelsPanel treats that as "hide the guided button", not an error.
  getEmbeddedSignupConfig: () => apiFetch(`/api/channels/meta-whatsapp/embedded-signup/config`).then(toJson),
  exchangeEmbeddedSignupCode: (payload: object) =>
    apiFetch(`/api/channels/meta-whatsapp/embedded-signup/exchange`, {
      method: "POST",
      ...jsonBody(payload),
    }).then(toJson),

  // Approved WhatsApp message templates (nlflow W5/W6) for the workflow Output
  // node's picker. Quiet on the statuses the picker renders itself: 404 = the
  // feature is switched off on this server (picker hidden), 403 = caller lacks
  // Manage channels, 400 = Meta refused the listing. [] = Meta Cloud API is
  // not the connected WhatsApp provider.
  listWhatsAppTemplates: () => apiFetch(`/api/channels/whatsapp/templates`).then(toJsonQuietOn([400, 403, 404])),

  // Google Workspace integration (Drive/Gmail/Calendar/Sheets) - one
  // "Connect Google" OAuth flow, not a form. getGoogleAuthorizeUrl() returns
  // the full Google consent URL to window.location.href to; the browser
  // leaves the app and Google's own redirect lands back on the backend's
  // /oauth/google/callback (not handled here - see GoogleOAuthController),
  // which 302s back to the console with a ?google=connected|error param.
  getGoogleAuthorizeUrl: () => oauthAuthorizeUrl(`/api/channels/google/authorize-url`),
  getGoogleConnection: () =>
    apiFetch(`/api/channels/google/status`).then((res) => (res.status === 204 ? null : toJson(res))),
  getGoogleOAuthConfig: (): Promise<{ configured: boolean; redirectUri: string; mode?: string; problem?: string }> =>
    apiFetch(`/api/channels/google/oauth-config`).then((res) => toJson(res) as Promise<{ configured: boolean; redirectUri: string; mode?: string; problem?: string }>),
  disconnectGoogle: () =>
    apiFetch(`/api/channels/google/connection`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  // Personal Google connect (V215 - GoogleOAuthController's /personal/* pair).
  // Same "leave the app, come back via the backend redirect" OAuth dance as
  // the tenant-wide flow above, except the minted consent URL's state carries
  // the caller's own Keycloak subject, so the resulting credential belongs to
  // THEM (per-user subject_key row; the admin-connected tenant-wide row stays
  // the fallback for members who never connect their own). Any authenticated
  // member may call these - unlike the admin-gated pair above, connecting or
  // revoking YOUR OWN Google account is deliberately not an admin action.
  // disconnectGooglePersonal only ever revokes the caller's own row (204 even
  // if they never connected one - idempotent, same as disconnectGoogle).
  getGooglePersonalAuthorizeUrl: () => oauthAuthorizeUrl(`/api/channels/google/personal/authorize-url`),
  disconnectGooglePersonal: () =>
    apiFetch(`/api/channels/google/personal/connection`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  // Incremental "Enable YouTube Publishing" consent (GoogleOAuthController#
  // youtubeUploadAuthorizeUrl) - additive to an existing tenant-wide Google
  // connection, admin-gated same as getGoogleAuthorizeUrl above. Requests
  // GoogleScopes.YOUTUBE_UPLOAD only once the tenant already has a Google
  // connection; the resulting scope shows up in a later getGoogleConnection()
  // call's grantedScopes, same as every other scope.
  getYoutubeUploadAuthorizeUrl: () => oauthAuthorizeUrl(`/api/channels/google/youtube-upload/authorize-url`),

  // X (Twitter) DMs - a two-step BYOK-then-OAuth connection (see
  // XCredential's javadoc): registerXApp stores the tenant's own X Developer
  // App Client ID/Secret first, THEN getXAuthorizeUrl() returns X's consent
  // URL to redirect the browser to, same "leave the app, come back via a
  // backend redirect" flow as Google above (lands on /oauth/x/callback, not
  // handled here - see XOAuthController).
  getXCredential: () => apiFetch(`/api/channels/x/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  registerXApp: (payload: object) =>
    apiFetch(`/api/channels/x/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  getXAuthorizeUrl: () => oauthAuthorizeUrl(`/api/channels/x/authorize-url`),
  disconnectX: () =>
    apiFetch(`/api/channels/x/connection`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendXMessage: (payload: object) =>
    apiFetch(`/api/channels/x/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setXDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/x/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Atlassian (Confluence/Jira) MCP connector - the atlassian catalog entry's
  // requiresOAuth branch (McpPanel.tsx) calls getAtlassianAuthorizeUrl() and
  // sends the browser to the returned URL, same "leave the app, come back
  // via a backend redirect" flow as Google/X above. Lands on
  // /oauth/atlassian/callback (not handled here - see
  // AtlassianOAuthController), which 302s back to /tools with a
  // ?atlassian=connected|error param. One-step, unlike X - no BYOK app
  // registration first (Atlassian's server supports Dynamic Client
  // Registration, so the platform registers itself once, not per tenant).
  getAtlassianAuthorizeUrl: () => oauthAuthorizeUrl(`/api/mcp/registry/atlassian/authorize-url`),
  disconnectAtlassian: () =>
    apiFetch(`/api/mcp/registry/atlassian/connection`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),

  // Telegram - a tenant's own Telegram Bot (#472). Unlike Discord, Telegram
  // has a real public webhook, so activation alone is enough for live
  // inbound routing (no separate Node gateway to deploy).
  getTelegramCredential: () =>
    apiFetch(`/api/channels/telegram/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateTelegramCredential: (payload: object) =>
    apiFetch(`/api/channels/telegram/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  // "Log out of Telegram" - marks DISCONNECTED, unlinks the bot->tenant
  // route AND calls Telegram's deleteWebhook so updates stop arriving.
  disconnectTelegramCredential: () =>
    apiFetch(`/api/channels/telegram/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendTelegramMessage: (payload: object) =>
    apiFetch(`/api/channels/telegram/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setTelegramDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/telegram/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Signal (core/omnichannel/signal, migration V600) - the operator's own
  // signal-cli REST API plus the registered number the agent speaks as.
  // Activation proves both against that server before storing anything.
  getSignalCredential: () =>
    apiFetch(`/api/channels/signal/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateSignalCredential: (payload: object) =>
    apiFetch(`/api/channels/signal/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  // Who the agent answers (unknown senders, groups) without reconnecting.
  updateSignalPolicy: (payload: object) =>
    apiFetch(`/api/channels/signal/credential/policy`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  disconnectSignalCredential: () =>
    apiFetch(`/api/channels/signal/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendSignalMessage: (payload: object) =>
    apiFetch(`/api/channels/signal/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setSignalDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/signal/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),
  // WhatsApp QR (core/omnichannel/whatsappqr, services/whatsapp-qr) - the tenant's OWN number,
  // linked as a companion device by scanning a QR. No Meta Business setup, no public webhook:
  // the bridge dials out, which is why this is the WhatsApp path that works on a personal
  // install. GET /session returns {status, bridgeStatus, qr, phoneNumber, configured} or 204.
  getWhatsAppQrSession: () =>
    apiFetch(`/api/channels/whatsapp-qr/session`).then((res) => (res.status === 204 ? null : toJson(res))),
  connectWhatsAppQrSession: () =>
    apiFetch(`/api/channels/whatsapp-qr/session`, { method: "POST" }).then(toJson),
  disconnectWhatsAppQrSession: () =>
    apiFetch(`/api/channels/whatsapp-qr/session`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendWhatsAppQrMessage: (payload: object) =>
    apiFetch(`/api/channels/whatsapp-qr/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setWhatsAppQrDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/whatsapp-qr/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),
  // Matrix (core/omnichannel/matrix) - a bot user on a homeserver the operator chooses. No webhook:
  // the platform pulls with /sync, so the credential response says whether this install runs the loop.
  getMatrixCredential: () =>
    apiFetch(`/api/channels/matrix/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateMatrixCredential: (payload: object) =>
    apiFetch(`/api/channels/matrix/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  disconnectMatrixCredential: () =>
    apiFetch(`/api/channels/matrix/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  updateMatrixPolicy: (payload: object) =>
    apiFetch(`/api/channels/matrix/policy`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  sendMatrixMessage: (payload: object) =>
    apiFetch(`/api/channels/matrix/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setMatrixDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/matrix/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Microsoft Teams (core/omnichannel/teams, migration V240) - a tenant's own
  // Azure Bot registration: activate is prove-before-persist (the backend
  // mints a real Bot Framework token first); send is approval-gated like
  // every other channel's /send; `to` is the Bot Framework conversation id.
  getTeamsCredential: () =>
    apiFetch(`/api/channels/teams/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateTeamsCredential: (payload: object) =>
    apiFetch(`/api/channels/teams/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  disconnectTeamsCredential: () =>
    apiFetch(`/api/channels/teams/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendTeamsMessage: (payload: object) =>
    apiFetch(`/api/channels/teams/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setTeamsDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/teams/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // iMessage through the operator's own BlueBubbles server on a Mac (core/omnichannel-social imessage, V610).
  // Activate proves the server address + password first; settings holds the sender allow-list, groups,
  // unknown-sender policy and inbound mode; send is approval-gated and `to` is a chat GUID or a phone/email.
  getImessageCredential: () =>
    apiFetch(`/api/channels/imessage/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateImessageCredential: (payload: object) =>
    apiFetch(`/api/channels/imessage/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  updateImessageSettings: (payload: object) =>
    apiFetch(`/api/channels/imessage/credential/settings`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  disconnectImessageCredential: () =>
    apiFetch(`/api/channels/imessage/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendImessage: (payload: object) =>
    apiFetch(`/api/channels/imessage/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setImessageDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/imessage/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Email/IMAP - a tenant's own support mailbox (#472, PRD Section 3.2's
  // Mail-Checking Agent made real). EmailInboundPollingJob checks it every 5
  // minutes; outbound replies go through the platform's shared SMTP relay.
  getEmailCredential: () =>
    apiFetch(`/api/channels/email/credential`).then((res) => (res.status === 204 ? null : toJson(res))),
  activateEmailCredential: (payload: object) =>
    apiFetch(`/api/channels/email/credential`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  disconnectEmailCredential: () =>
    apiFetch(`/api/channels/email/credential`, { method: "DELETE" }).then((res) =>
      res.status === 204 ? null : toJson(res)
    ),
  sendEmailMessage: (payload: object) =>
    apiFetch(`/api/channels/email/send`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  setEmailDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/email/default-agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Single real aggregate over every channel's connection status + paired
  // session counts + a cross-channel recent-inbound log (#472,
  // ChannelStatusController) - the AXON Channels screen's grid/topbar/log
  // all read from this one call instead of firing nine separate requests.
  getChannelsStatus: () => apiFetch(`/api/channels/status`).then(toJson<ChannelsStatusResponse>),

  // "Test connection" (ChannelHealthController) - runs the channel's real
  // activation-time validation call against the stored credential and
  // reports {ok, identity, latencyMs, detail}. Proves a stored credential
  // is actually live right now, not just present.
  testChannelConnection: (key: string) => apiFetch(`/api/channels/health/${key}`, { method: "POST" }).then(toJson),

  // Inbound-channel pairing (Autonomy/Guardrails Strategy Stage A) - viewing
  // the pending queue is open to any authenticated tenant member; approve
  // (POST) and reject (DELETE) are gated server-side to ROLE_tenant-admin.
  listPendingPairings: () => apiFetch(`/api/channels/pairing/pending`).then(toJson),
  approvePairing: (sessionId: string) =>
    apiFetch(`/api/channels/pairing/${sessionId}/approve`, { method: "POST" }).then(toJson),
  rejectPairing: (sessionId: string) =>
    apiFetch(`/api/channels/pairing/${sessionId}`, { method: "DELETE" }).then(toJson),
  // "Unmap" a paired conversation back into the pending-pairing queue
  // (POST /{id}/unpair) - fresh pairing code, history kept. Used by the
  // rebuilt /admin/inbox details pane.
  unpairChannelSession: (sessionId: string) =>
    apiFetch(`/api/channels/pairing/${sessionId}/unpair`, { method: "POST" }).then(toJson),
  // #590 follow-up ("wrong agent for telegram message sending") - re-points
  // one already-existing conversation to a different agent. Gated server-
  // side to ROLE_tenant-admin, same tier as approve/reject above.
  rebindChannelSessionAgent: (sessionId: string, agentId: string) =>
    apiFetch(`/api/channels/pairing/${sessionId}/agent`, { method: "PUT", ...jsonBody({ agentId }) }),

  // Contacts & request mapping (/admin/contacts, migration V238) - the
  // tenant's contact directory, contact groups (group-level agent routing),
  // CSV/XLSX import, and the per-channel "does a new sender need approval"
  // mode. All writes are ROLE_tenant-admin server-side; reads are
  // authenticated-member, same split as the pairing endpoints above.
  listChannelContacts: (channel?: string, groupId?: string) => {
    const params = new URLSearchParams();
    if (channel) params.set("channel", channel);
    if (groupId) params.set("groupId", groupId);
    // No limit: the complete list, as before (the server only caps when a limit is sent).
    const qs = params.toString();
    return apiFetch(`/api/channels/contacts${qs ? `?${qs}` : ""}`).then(toJson);
  },
  // One page of the directory (newest first). The server caps each response, so the panel
  // pages with offset and reads X-Total-Count / X-Has-More instead of assuming it has everything.
  listChannelContactsPage: (channel?: string, groupId?: string, offset = 0, limit = 500) => {
    const params = new URLSearchParams();
    if (channel) params.set("channel", channel);
    if (groupId) params.set("groupId", groupId);
    return apiFetch(`/api/channels/contacts?${pagedQuery(params, offset, limit)}`).then((res) =>
      toPaged(res, offset, limit)
    );
  },
  createChannelContact: (payload: object) =>
    apiFetch(`/api/channels/contacts`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  updateChannelContact: (id: string, payload: object) =>
    apiFetch(`/api/channels/contacts/${id}`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  deleteChannelContact: (id: string) => apiFetch(`/api/channels/contacts/${id}`, { method: "DELETE" }),
  listContactGroups: () => apiFetch(`/api/channels/contact-groups`).then(toJson<ContactGroup[]>),
  createContactGroup: (payload: object) =>
    apiFetch(`/api/channels/contact-groups`, { method: "POST", ...jsonBody(payload) }).then(toJson),
  updateContactGroup: (id: string, payload: object) =>
    apiFetch(`/api/channels/contact-groups/${id}`, { method: "PUT", ...jsonBody(payload) }).then(toJson),
  deleteContactGroup: (id: string) => apiFetch(`/api/channels/contact-groups/${id}`, { method: "DELETE" }),
  // Multipart import - same "leave headers unset so fetch computes the
  // multipart boundary" posture as cloneElevenLabsVoice above.
  importChannelContactsFile: (channel: string, file: Blob, groupId?: string) => {
    const formData = new FormData();
    formData.append("file", file);
    const params = new URLSearchParams({ channel });
    if (groupId) params.set("groupId", groupId);
    return apiFetch(`/api/channels/contacts/import?${params}`, { method: "POST", body: formData }).then(toJson);
  },
  importChannelContactsText: (channel: string, text: string, groupId?: string) =>
    apiFetch(`/api/channels/contacts/import-rows`, {
      method: "POST",
      ...jsonBody({ channel, groupId: groupId || null, text }),
    }).then(toJson),
  listChannelRouting: () => apiFetch(`/api/channels/routing`).then(toJson),
  setChannelRouting: (channel: string, agentId: string, autoApproveNewSenders: boolean) =>
    apiFetch(`/api/channels/routing/${encodeURIComponent(channel)}`, {
      method: "PUT",
      ...jsonBody({ agentId, autoApproveNewSenders }),
    }).then(toJson),

  // Manual/API-triggered WhatsApp send (Omnichannel Stage 2) - goes through
  // the same HumanApprovalGateService.evaluate() any future auto-reply path
  // will, so a "requires approval" policy can route this straight into the
  // approvals queue instead of sending immediately.
  sendWhatsApp: (to: string, message: string) =>
    apiFetch(`/api/channels/whatsapp/send`, {
      method: "POST",
      ...jsonBody({ to, message }),
    }).then(toJson),
  // Gated server-side to ROLE_tenant-admin - a real per-tenant config write. The task is
  // required and an open-ended agent answers 409 until acknowledgeOpenEnded is sent
  // (docs/channels/whatsapp-policy.md); both are expected states, so they come back as a
  // result instead of an error toast.
  setWhatsAppDefaultAgent: async (
    agentId: string,
    task: string,
    acknowledgeOpenEnded = false,
  ): Promise<WhatsAppBindResult> => {
    const res = await apiFetch(`/api/channels/whatsapp/default-agent`, {
      method: "PUT",
      ...jsonBody({ agentId, task, acknowledgeOpenEnded }),
    });
    if (res.status === 400 || res.status === 409) {
      const body: { code?: string; message?: string; reasons?: unknown[] } | null = await res
        .json()
        .catch((): null => null);
      if (body && (body.code === "WHATSAPP_TASK_REQUIRED" || body.code === "WHATSAPP_OPEN_ENDED_AGENT")) {
        return {
          ok: false,
          code: body.code as WhatsAppBindResult["code"],
          message: String(body.message || ""),
          reasons: Array.isArray(body.reasons) ? body.reasons.map(String) : [],
        };
      }
      // Some other 400/409: let the shared handler raise the normal error.
      return toJson(new Response(JSON.stringify(body), { status: res.status, headers: res.headers }));
    }
    await toJson(res);
    return { ok: true };
  },
  whatsAppQuality: () =>
    apiFetch(`/api/channels/whatsapp-compliance/quality`).then(
      toJsonQuietOn([400, 404, 502, 503]) as (r: Response) => Promise<WhatsAppQuality>,
    ),
  recordWhatsAppOptIn: (phone: string, source: string, evidence?: string) =>
    apiFetch(`/api/channels/whatsapp-compliance/opt-in`, {
      method: "POST",
      ...jsonBody({ phone, source, evidence }),
    }).then(toJson),
  /** DELETE /api/channels/whatsapp-compliance/opt-in?phone= ; resolves false when no record exists (404). */
  revokeWhatsAppOptIn: (phone: string): Promise<boolean> =>
    apiFetch(`/api/channels/whatsapp-compliance/opt-in?phone=${encodeURIComponent(phone)}`, {
      method: "DELETE",
    }).then(async (res) => {
      if (res.status === 404) return false;
      await toJson(res);
      return true;
    }),
  // Plain SMS over the same Twilio number (2026-10-14) - same gate as WhatsApp.
  setSmsDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/sms/default-agent`, {
      method: "PUT",
      ...jsonBody({ agentId }),
    }).then(toJson),

  // Master Roadmap 2026 item #17 fast-follow (Voice ChannelsPanel UI + DTMF)
  // - VOICE reuses the tenant's existing Twilio credential (no separate
  // activate/deactivate here, see VoiceChannelController's javadoc), so the
  // only writes are which agent answers by default and the DTMF menu below.
  setVoiceDefaultAgent: (agentId: string) =>
    apiFetch(`/api/channels/voice/default-agent`, {
      method: "PUT",
      ...jsonBody({ agentId }),
    }).then(toJson),
  getVoiceDtmfMenu: () => apiFetch(`/api/channels/voice/dtmf-menu`).then(toJson<DtmfMapping[]>),
  setVoiceDtmfMapping: (digit: string, agentId: string, label: string) =>
    apiFetch(`/api/channels/voice/dtmf-menu/${encodeURIComponent(digit)}`, {
      method: "PUT",
      ...jsonBody({ agentId, label }),
    }).then(toJson<DtmfMapping>),
  // 204 with no body, so toJson resolves null.
  deleteVoiceDtmfMapping: (digit: string) =>
    apiFetch(`/api/channels/voice/dtmf-menu/${encodeURIComponent(digit)}`, { method: "DELETE" }).then(toJson<void>),
};
