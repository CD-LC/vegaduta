// ---------------------------------------------------------------------------
// /integrations hub — page data (2026-08-14).
//
// Grounding notes: every claim here maps to shipped code on main —
// core/omnichannel (channel adapters), core/sarvam, core/elevenlabs,
// core/google, core/mcp + the MCP registry review flow, the E2B toggle in
// core/agents. Honest-limitation
// sections are deliberate; do not soften them. See lib/seo-hub.ts for the
// no-fiction rule.
//
// Content sync 2026-08-17: added the "atlassian" page (Jira & Confluence).
// It is grounded in core/mcp/atlassian — AtlassianOAuthService (Authorization
// Code + PKCE, AES-256-GCM encrypted state), AtlassianPlatformClientService
// (ONE platform-wide client via Dynamic Client Registration, which is why the
// page can honestly say "no app to register"), and the oauthProvider branch in
// DynamicMcpConnectionManager#remoteHeaders that mints the token per connect
// instead of storing a static header. The tool surface is deliberately
// described as "whatever Atlassian's server publishes" rather than an
// enumerated list: it is Atlassian's remote server, not ours, so naming
// specific tools here would be a claim this repo cannot keep true.
//
// Content sync 2026-10-03.
// - Every "Temporal-backed workflow" is now just "workflow": Temporal is
//   opt-in and OFF by default (lib/platform-facts.ts header).
// - "answers in 22 languages" is gone. 22 is the number of Indian INTERFACE
//   locales (30 in total, lib/i18n-config.ts); reply language depends on the
//   model, and "speech in 22 languages" is on the do-not-claim list.
// - The "home-assistant" page is REMOVED. Home Assistant connectors are on
//   the do-not-claim list: mcp-servers/home-assistant has only ever run
//   against a mock server (McpCatalogService's own comment says so), and the
//   page also described three tools where the connector has more. Restore it
//   only after a smoke test against a live Home Assistant.
// - "e2b": the key is NOT platform-managed. E2bCredential is one row per
//   tenant, and run_code additionally needs a code-execution template the
//   tenant builds (code_execution_template_id). The page now says so.
// - "crm": still no native Salesforce/HubSpot/Zoho connector, but the page
//   now mentions the built-in leads list (core/omnichannel/crm LeadService,
//   LeadStage; web/app/admin/leads) and the CRM_LEAD / BULK_EMAIL workflow
//   nodes, with reply and bounce detection from
//   core/omnichannel/email/EmailReplyTracker.
// ---------------------------------------------------------------------------

import type { HubPage } from "../lib/seo-hub";
import { MESSAGING_INTEGRATIONS } from "./integrations-data-messaging";
import { SERVICE_INTEGRATIONS } from "./integrations-data-services";

export const INTEGRATIONS: HubPage[] = [
  ...MESSAGING_INTEGRATIONS,
  ...SERVICE_INTEGRATIONS,
];
