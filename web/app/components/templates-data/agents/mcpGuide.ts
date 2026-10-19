// ---------------------------------------------------------------- MCP guide
//
// "It should suggest which MCP should be enabled" - the missing half of the
// gallery. A template could always say what to *prompt*, never what to
// *connect*, so every entry below that needs an outside system now names the
// exact catalog server, whether it is required or merely better-with, and
// what it costs to turn on.
//
// Everything here is deliberately constrained to facts the platform can
// actually honour:
//
//  * MCP_CATALOG_KEYS is the FIXED 17-entry curated catalog served by
//    GET /api/mcp/registry/catalog (McpCatalogService). Nothing outside this
//    list may be suggested: a self-service STDIO registration that is not a
//    catalog entry needs platform-staff review (McpReviewStatus) before it is
//    even allowed to connect, so suggesting one would be suggesting a wait.
//  * MCP_SERVER_SETUP mirrors that catalog's `ready` / `requiredEnvVars` /
//    `requiresOAuth` shape, so the gallery can tell "one click, no
//    credentials" apart from "go and mint a token first" WITHOUT pretending
//    to know the tenant's state. The five credentialFree servers are the only
//    genuinely one-click ones.
//  * MCP_PLAN_CONNECTOR_LIMIT is the real ceiling enforced in
//    McpRegistryService.register (McpConnectorPlanLimits). A FREE tenant gets
//    TWO connectors total, catalog-only. A guide that recommends three
//    servers to a FREE tenant is recommending a 4xx, so consumers must show
//    the cap next to the suggestion.
//  * MCP_ALLOW_LIST_CAVEAT_KEY covers the single biggest wiring hazard in the
//    whole feature, and is why this is a guide rather than a button. Enabling
//    a server on /tools does NOT reach an agent that has a non-empty tool
//    allow-list: MCP tools arrive renamed as `mcp__<server>__<tool>`
//    (NamespacedMcpToolCallback), an EMPTY allowedToolNames means "no
//    restriction" and a NON-EMPTY one is a hard deny-everything-else. The
//    console templates here ship no allow-list at all, so they are
//    unaffected; the configured DB templates (V223) are, and the agent's own
//    Tools screen (GET /api/agents/{id}/available-tools, then
//    PATCH /api/agents/{id}/tool-access) is where that is fixed.
// ---------------------------------------------------------------------------
// DEAD CODE AS OF 2026-09-12 - READ THIS BEFORE USING ANYTHING BELOW.
//
// Everything from here to the end of templateSetupSteps() (MCP_CATALOG_KEYS,
// MCP_SERVER_SETUP, MCP_PLAN_CONNECTOR_LIMIT, MCP_CATALOG_ONLY_PLANS,
// TEMPLATE_SETUP_STEPS, templateSetupSteps) has ZERO references outside this
// file - verified by grep across web/app on 2026-09-12.
//
// It was written in the same fan-out as the layer that actually ships, by a
// different agent, and the two were already found DISAGREEING with each other
// (one declared optional google-maps for two templates that the live map had
// no entry for at all). Two exported MCP vocabularies in one codebase drift;
// that is the whole reason this banner exists rather than a silent leftover.
//
// THE LIVE LAYER IS:
//   web/app/components/agents/templatePrereqs.ts   - the data
//   web/app/components/agents/TemplateSetupGuide.tsx - the rendered guide
//   mounted from web/app/agents/templates/page.tsx
// Add MCP/prerequisite facts THERE. If you extend the block below instead,
// nothing you write will ever reach a user.
//
// Not deleted outright only because the types here may be referenced by the
// template entries in this same file; removing it is a deliberate cleanup
// task, not a drive-by edit. Either merge it into templatePrereqs.ts or
// delete it - do not grow it.
// ---------------------------------------------------------------------------
export const MCP_CATALOG_KEYS = [
  "filesystem",
  "github",
  "postgres",
  "brave-search",
  "slack",
  "puppeteer",
  "playwright-vision",
  "gitlab",
  "google-maps",
  "google-drive",
  "sqlite",
  "memory",
  "sequential-thinking",
  "sentry",
  "code-intel",
  "home-assistant",
  "atlassian",
] as const;

export type McpCatalogKey = (typeof MCP_CATALOG_KEYS)[number];

/**
 * How a template relates to a server.
 *
 * - `required` - the template is not worth importing without it.
 * - `optional` - it works without it and gets better with it.
 *
 * `oneOf` means pick exactly ONE of the listed servers (github OR gitlab),
 * not both: connecting both would burn both of a FREE tenant's two connector
 * slots on one capability.
 */
export type McpRequirement = "required" | "optional";

export type McpSuggestion = {
  servers: readonly McpCatalogKey[];
  requirement: McpRequirement;
  oneOf?: boolean;
};

/**
 * Per-server activation cost, mirroring the catalog entry's own fields.
 * `credentialFree` servers correspond to catalog `ready: true` with no
 * required env vars - the one-click "Activate" path in McpPanel. Everything
 * else opens a credential form (or, for Atlassian, an OAuth 2.1 + PKCE
 * redirect - a pasted bearer token always 401s there, so `oauth` has to be
 * checked BEFORE the credential-form branch).
 */
export const MCP_SERVER_SETUP: Record<
  McpCatalogKey,
  { credentialFree: boolean; oauth: boolean; requiredEnvVars: readonly string[] }
> = {
  filesystem: { credentialFree: true, oauth: false, requiredEnvVars: [] },
  puppeteer: { credentialFree: true, oauth: false, requiredEnvVars: [] },
  "playwright-vision": { credentialFree: true, oauth: false, requiredEnvVars: [] },
  memory: { credentialFree: true, oauth: false, requiredEnvVars: [] },
  "sequential-thinking": { credentialFree: true, oauth: false, requiredEnvVars: [] },
  github: { credentialFree: false, oauth: false, requiredEnvVars: ["GITHUB_PERSONAL_ACCESS_TOKEN"] },
  gitlab: { credentialFree: false, oauth: false, requiredEnvVars: ["GITLAB_PERSONAL_ACCESS_TOKEN"] },
  postgres: { credentialFree: false, oauth: false, requiredEnvVars: ["MCP_POSTGRES_CONNECTION_STRING"] },
  sqlite: { credentialFree: false, oauth: false, requiredEnvVars: ["MCP_SQLITE_DB_PATH"] },
  "brave-search": { credentialFree: false, oauth: false, requiredEnvVars: ["BRAVE_API_KEY"] },
  slack: { credentialFree: false, oauth: false, requiredEnvVars: ["SLACK_BOT_TOKEN", "SLACK_TEAM_ID"] },
  "google-maps": { credentialFree: false, oauth: false, requiredEnvVars: ["GOOGLE_MAPS_API_KEY"] },
  "google-drive": { credentialFree: false, oauth: false, requiredEnvVars: ["GDRIVE_OAUTH_CREDENTIALS_PATH"] },
  sentry: { credentialFree: false, oauth: false, requiredEnvVars: ["SENTRY_AUTH_TOKEN"] },
  "code-intel": {
    credentialFree: false,
    oauth: false,
    requiredEnvVars: ["CODE_INTEL_URL", "CODE_INTEL_INTERNAL_TOKEN", "CODE_INTEL_TENANT"],
  },
  "home-assistant": { credentialFree: false, oauth: false, requiredEnvVars: ["HASS_URL", "HASS_TOKEN"] },
  atlassian: { credentialFree: false, oauth: true, requiredEnvVars: [] },
};

/** Real ceiling from McpConnectorPlanLimits; `null` = uncapped. */
export const MCP_PLAN_CONNECTOR_LIMIT: Record<"FREE" | "PREMIUM" | "UNLIMITED", number | null> = {
  FREE: 2,
  PREMIUM: 10,
  UNLIMITED: null,
};

/** FREE tenants may only register entries from the curated catalog above. */
export const MCP_CATALOG_ONLY_PLANS = ["FREE"] as const;

/** See the allow-list note above; the i18n body lives at this key. */
export const MCP_ALLOW_LIST_CAVEAT_KEY = "mcpAllowListWarning";

/**
 * Where to send someone to actually turn a server on. /tools has no
 * "preselect this card" parameter - the only params it reads are its
 * free-text `query` search box and the `?atlassian=` OAuth callback - so the
 * honest deep link pre-fills the search rather than pretending to open the
 * card.
 */
export function mcpToolsHref(server?: McpCatalogKey) {
  return server ? "/tools?query=" + encodeURIComponent(server) : "/tools";
}

function camelize(kebab: string) {
  return kebab.replace(/-([a-z])/g, (_: string, c: string) => c.toUpperCase());
}

export function mcpServerLabelKey(server: McpCatalogKey) {
  const base = camelize(server);
  return `mcpServer${base[0].toUpperCase()}${base.slice(1)}Label`;
}

export function mcpServerDescriptionKey(server: McpCatalogKey) {
  const base = camelize(server);
  return `mcpServer${base[0].toUpperCase()}${base.slice(1)}Description`;
}

export function mcpRequirementLabelKey(requirement: McpRequirement) {
  return `mcpRequirement${requirement[0].toUpperCase()}${requirement.slice(1)}Label`;
}

// ------------------------------------------------------- "guide on a click"
//
// The ordered prerequisite checklist a template detail view can render. These
// are the things that silently make an imported agent useless, in the order
// they bite. Knowledge is first on purpose: a knowledge-grounded template
// with an empty collection is strictly worse than a bare chat model, and that
// is true of roughly three quarters of this gallery - it matters more than
// any MCP server. FREE allows exactly one knowledge collection, so the
// knowledge step's body says so rather than letting the user find out at the
// upload. Each id has a Title and a Body key in the agentTemplates namespace,
// plus an `href` to the screen that fixes it.
export const TEMPLATE_SETUP_STEPS = [
  { id: "knowledge", href: "/knowledge" },
  { id: "channel", href: "/admin/channels" },
  { id: "provider", href: "/providers" },
  { id: "mcp", href: "/tools" },
  { id: "toolAccess", href: "/agents" },
] as const;

export type TemplateSetupStepId = (typeof TEMPLATE_SETUP_STEPS)[number]["id"];

export function setupStepTitleKey(id: TemplateSetupStepId) {
  return `setupStep${id[0].toUpperCase()}${id.slice(1)}Title`;
}

export function setupStepBodyKey(id: TemplateSetupStepId) {
  return `setupStep${id[0].toUpperCase()}${id.slice(1)}Body`;
}

/**
 * The prerequisite steps that actually apply to one template, in order.
 * Deliberately conservative: a step only appears when this template's own
 * data implies it, so the checklist never pads itself with advice the user
 * cannot act on. `customerFacing` templates additionally get the channel
 * step, because an agent meant for customers that lives only in the console
 * chat window has not shipped.
 */
export function templateSetupSteps(template: {
  suggestKnowledgeBase?: boolean;
  customerFacing?: boolean;
  mcp?: readonly McpSuggestion[];
}): TemplateSetupStepId[] {
  const steps: TemplateSetupStepId[] = [];
  if (template.suggestKnowledgeBase) steps.push("knowledge");
  if (template.customerFacing) steps.push("channel");
  if (template.mcp && template.mcp.length > 0) {
    steps.push("mcp");
    steps.push("toolAccess");
  }
  return steps;
}

/** Flattens a template's suggestions to the distinct servers it names. */
export function templateMcpServers(template: { mcp?: readonly McpSuggestion[] }): McpCatalogKey[] {
  const seen = new Set<McpCatalogKey>();
  for (const suggestion of template.mcp ?? []) {
    for (const server of suggestion.servers) seen.add(server);
  }
  return [...seen];
}
