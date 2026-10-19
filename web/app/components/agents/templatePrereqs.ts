// What a template actually needs before the agent it creates does real work.
//
// Why this file exists at all: a template in this product pre-fills a system
// prompt and nothing else. It cannot express "this agent needs the github MCP
// server switched on" - AgentSnapshot has no field for MCP servers, and
// neither the console gallery (components/agentTemplates.ts) nor the seeded
// control_plane.community_template rows reference any of the 17 catalog
// servers. So the mapping lives here, as plain typed data the setup guide
// reads, rather than being invented at render time.
//
// Rules this file holds itself to:
//   - Only the 17 curated catalog servers may be suggested. A self-service
//     STDIO registration that is NOT a catalog entry needs platform-staff
//     review before it may connect (McpReviewStatus), and a FREE tenant is
//     restricted to catalog entries outright, so suggesting anything else
//     would be suggesting a POST the API refuses.
//   - Nothing here promises a capability that is not shipped. Where a
//     template genuinely needs no MCP server, it says so instead of padding
//     the list.
//   - The live catalog (GET /api/mcp/registry/catalog) stays authoritative
//     for display name, description, docs URL and credential fields. The
//     facts duplicated below exist only so the guide can still render an
//     honest checklist when that fetch fails.

import { CATEGORY_AUDIENCE } from "../agentTemplates";

/** The 17 curated servers in McpCatalogService. `key` and `name` are identical for every one of them, which is what lets a registration be matched back to a catalog entry by name. */
export const MCP_SERVER_KEYS = [
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

export type McpServerKey = (typeof MCP_SERVER_KEYS)[number];

export interface McpServerFacts {
  /** Env vars the connector needs typed in. Empty = nothing to paste. */
  readonly requiredEnvVars: readonly string[];
  /** OAuth 2.1 + PKCE instead of a typed credential (atlassian only). A pasted bearer token always 401s there. */
  readonly requiresOAuth: boolean;
  /**
   * "none"        - one-click Activate, already wired statically.
   * "apiKey"      - a token/key you can paste into the connect form.
   * "oauth"       - a consent screen, nothing to paste.
   * "serverPath"  - a FILE PATH on the server host, not a pasteable value; needs whoever runs the deployment.
   * "deployment"  - only works if the deployment itself was set up for it (bind mounts, an internal service URL).
   */
  readonly credential: "none" | "apiKey" | "oauth" | "serverPath" | "deployment";
}

export const MCP_SERVER_FACTS: Record<McpServerKey, McpServerFacts> = {
  filesystem: { requiredEnvVars: [], requiresOAuth: false, credential: "none" },
  puppeteer: { requiredEnvVars: [], requiresOAuth: false, credential: "none" },
  "playwright-vision": { requiredEnvVars: [], requiresOAuth: false, credential: "none" },
  memory: { requiredEnvVars: [], requiresOAuth: false, credential: "none" },
  "sequential-thinking": { requiredEnvVars: [], requiresOAuth: false, credential: "none" },
  github: { requiredEnvVars: ["GITHUB_PERSONAL_ACCESS_TOKEN"], requiresOAuth: false, credential: "apiKey" },
  gitlab: { requiredEnvVars: ["GITLAB_PERSONAL_ACCESS_TOKEN"], requiresOAuth: false, credential: "apiKey" },
  "brave-search": { requiredEnvVars: ["BRAVE_API_KEY"], requiresOAuth: false, credential: "apiKey" },
  "google-maps": { requiredEnvVars: ["GOOGLE_MAPS_API_KEY"], requiresOAuth: false, credential: "apiKey" },
  sentry: { requiredEnvVars: ["SENTRY_AUTH_TOKEN"], requiresOAuth: false, credential: "apiKey" },
  slack: { requiredEnvVars: ["SLACK_BOT_TOKEN", "SLACK_TEAM_ID"], requiresOAuth: false, credential: "apiKey" },
  postgres: { requiredEnvVars: ["MCP_POSTGRES_CONNECTION_STRING"], requiresOAuth: false, credential: "apiKey" },
  // A server-side file path, not a value anyone can paste from their laptop.
  "google-drive": {
    requiredEnvVars: ["GDRIVE_OAUTH_CREDENTIALS_PATH"],
    requiresOAuth: false,
    credential: "serverPath",
  },
  sqlite: { requiredEnvVars: ["MCP_SQLITE_DB_PATH"], requiresOAuth: false, credential: "serverPath" },
  "home-assistant": { requiredEnvVars: ["HASS_URL", "HASS_TOKEN"], requiresOAuth: false, credential: "deployment" },
  "code-intel": {
    requiredEnvVars: ["CODE_INTEL_URL", "CODE_INTEL_INTERNAL_TOKEN", "CODE_INTEL_TENANT"],
    requiresOAuth: false,
    credential: "deployment",
  },
  atlassian: { requiredEnvVars: [], requiresOAuth: true, credential: "oauth" },
};

/** The five that need zero credentials - ready=true in the catalog, already wired in application-mcp.yml, so the card on /tools is a single Activate click. */
export const ZERO_CREDENTIAL_MCP_SERVERS: readonly McpServerKey[] = MCP_SERVER_KEYS.filter(
  (key) => MCP_SERVER_FACTS[key].credential === "none"
);

export function mcpNeedsNoCredential(key: McpServerKey): boolean {
  return MCP_SERVER_FACTS[key].credential === "none";
}

// McpConnectorPlanLimits, enforced in McpRegistryService.register. FREE is
// additionally restricted to catalog entries only (allowsCustomServers).
// null = no plan-level ceiling.
export const MCP_CONNECTOR_CAP: Record<string, number | null> = {
  FREE: 2,
  PREMIUM: 10,
  UNLIMITED: null,
};

export function mcpConnectorCapFor(plan: string | null | undefined): number | null {
  if (!plan) return null;
  const cap = MCP_CONNECTOR_CAP[plan];
  return cap === undefined ? null : cap;
}

export type Necessity = "required" | "optional";

export interface McpSuggestion {
  readonly server: McpServerKey;
  readonly necessity: Necessity;
  /** Key under the templateSetupGuide en.json namespace explaining what the server buys this agent. */
  readonly reasonKey: string;
  /** Suggestions sharing a group are interchangeable - pick one, not both (github OR gitlab). */
  readonly alternativeGroup?: string;
  /** Key under templateSetupGuide for a warning to read BEFORE switching this on. */
  readonly cautionKey?: string;
}

export type KnowledgeNeed = "required" | "recommended" | "none";

export interface TemplatePrereqs {
  /** "required" = the agent's whole job is answering from documents; without content it is worse than a bare chat model. */
  readonly knowledge: KnowledgeNeed;
  /** Meant to answer real people, so an inbound channel is part of the setup rather than an afterthought. */
  readonly channel: boolean;
  /** Needs the Google Workspace OAuth connection (native google_gmail / google_calendar / google_drive / google_sheets). NOT the google-drive MCP entry. */
  readonly google: boolean;
  /** Needs run_code: a tenant E2B credential AND the agent's own code-execution toggle. */
  readonly e2b: boolean;
  /** Needs at least one indexed repository and code-intel enabled on the deployment. */
  readonly codeIntel: boolean;
  readonly mcp: readonly McpSuggestion[];
}

const NO_MCP: readonly McpSuggestion[] = [];

// ---------------------------------------------------------------------------
// Console gallery (components/agentTemplates.ts). Keys are that file's own
// template keys. Anything absent falls through to the derived default in
// prereqsForConsoleTemplate() - which is deliberate: the gallery grows, and a
// new template should get an honest "no MCP server needed" rather than a
// guessed one.
// ---------------------------------------------------------------------------
const CONSOLE_TEMPLATE_MCP: Record<string, readonly McpSuggestion[]> = {
  // Engineering - the only category where an MCP server is genuinely
  // load-bearing rather than a nice-to-have.
  "code-review-assistant": [
    { server: "github", necessity: "required", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "gitlab", necessity: "required", reasonKey: "reasonRepo", alternativeGroup: "repo" },
  ],
  "git-commit-pr-writer": [
    { server: "github", necessity: "required", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "gitlab", necessity: "required", reasonKey: "reasonRepo", alternativeGroup: "repo" },
  ],
  "incident-triage-assistant": [
    { server: "sentry", necessity: "optional", reasonKey: "reasonErrors" },
    { server: "github", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "gitlab", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "slack", necessity: "optional", reasonKey: "reasonTeamChat" },
  ],
  "debugging-assistant": [{ server: "sentry", necessity: "optional", reasonKey: "reasonErrors" }],
  "test-case-generator": [
    { server: "github", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "gitlab", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
  ],
  "technical-docs-writer": [
    { server: "github", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "gitlab", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "atlassian", necessity: "optional", reasonKey: "reasonWikiDocs" },
  ],

  // Everyday life / personal.
  // Required for web lookups UNLESS the deployment offers built-in deep
  // research - see DEEP_RESEARCH_COVERS in prereqsForConsoleTemplate, which
  // relaxes this to optional only when the caller has confirmed that.
  "research-assistant": [{ server: "brave-search", necessity: "required", reasonKey: "reasonWebSearch" }],
  "travel-planner": [
    { server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" },
    { server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" },
  ],
  "home-organizer": [
    {
      server: "home-assistant",
      necessity: "optional",
      reasonKey: "reasonSmartHome",
      cautionKey: "cautionSmartHome",
    },
  ],
  "job-search-assistant": [{ server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" }],

  // Customer & sales.
  "sales-lead-qualifier": [{ server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" }],
  "social-media-content": [{ server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" }],
  "customer-support-triage": [{ server: "slack", necessity: "optional", reasonKey: "reasonEscalate" }],
  "multilingual-support-agent": [{ server: "slack", necessity: "optional", reasonKey: "reasonEscalate" }],

  // Internal operations.
  "project-manager-assistant": [
    { server: "atlassian", necessity: "optional", reasonKey: "reasonIssueTracker" },
    { server: "slack", necessity: "optional", reasonKey: "reasonTeamChat" },
  ],
  "meeting-notes-summarizer": [{ server: "slack", necessity: "optional", reasonKey: "reasonTeamChat" }],
  "internal-kb-qa": [{ server: "atlassian", necessity: "optional", reasonKey: "reasonWikiDocs" }],

  // Industry verticals.
  "ecommerce-order-support": [
    { server: "postgres", necessity: "optional", reasonKey: "reasonDatabase" },
    { server: "puppeteer", necessity: "optional", reasonKey: "reasonBrowseSite" },
  ],
  "property-listing-assistant": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],
  "real-estate-lead-follow-up": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],
  "restaurant-reservation-assistant": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],
  "hotel-guest-services": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],
  "logistics-dispatch-assistant": [
    { server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" },
    { server: "postgres", necessity: "optional", reasonKey: "reasonDatabase" },
  ],
  "fleet-maintenance-assistant": [{ server: "postgres", necessity: "optional", reasonKey: "reasonDatabase" }],
  // Industry-expansion templates. Only the two that are genuinely
  // distance-shaped get a server; the rest of that batch is knowledge-base and
  // channel work, and listing a server against them would be padding.
  "freight-quotation-assistant": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],
  "tour-package-assistant": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],
  // Same distance/locality shape, and agentTemplates.ts already records the
  // same optional suggestion against both - without these two rows the guide
  // answered "nothing needed" for a template the gallery says wants maps.
  "project-sales-enquiry-assistant": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],
  "hotel-pre-stay-assistant": [{ server: "google-maps", necessity: "optional", reasonKey: "reasonMaps" }],

  // Explicitly none - recorded rather than omitted, so it reads as considered
  // and rejected instead of forgotten. Healthcare and legal are deliberate: a
  // patient-intake or legal-intake agent reaching a browser, a database or the
  // open web is a liability, not a feature.
  "clinic-appointment-assistant": NO_MCP,
  "patient-intake-assistant": NO_MCP,
  "legal-intake-assistant": NO_MCP,
  "legal-document-drafting": NO_MCP,
  "ecommerce-product-recommender": NO_MCP,
  "homework-helper": NO_MCP,
  "exam-prep-coach": NO_MCP,
  "language-learning-buddy": NO_MCP,
  "health-companion": NO_MCP,
  "family-tech-helper": NO_MCP,
  "finance-bills-tracker": NO_MCP,
  "linkedin-networking-coach": NO_MCP,
  "daily-planner": NO_MCP,
  "meal-planner": NO_MCP,
  "writing-editor": NO_MCP,
  "hr-onboarding-assistant": NO_MCP,
  "contract-policy-review": NO_MCP,
  // Works on attached files inside the code sandbox - no outside system.
  "data-analyst": NO_MCP,

  // Audience starting points (wave 3). Search is a bonus for the two that
  // read the open web; everything else works from what the person pastes or
  // uploads. The deep-research bonus on the literature review is a capability
  // (templateCapabilities.ts), not a connector, so it is not listed here.
  "literature-review-assistant": [{ server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" }],
  "daily-briefing-writer": [{ server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" }],
  "notes-to-revision-kit": NO_MCP,
  "explain-it-simply-tutor": NO_MCP,
  "resume-cover-letter-tailor": NO_MCP,
  "proposal-invoice-drafter": NO_MCP,
  "client-call-to-actions": NO_MCP,
  "whatsapp-reply-drafter": NO_MCP,
  // Same sandbox mechanism as data-analyst - no outside system.
  "sales-sheet-analyst": NO_MCP,
  "pre-pr-review-buddy": NO_MCP,
  // Enterprise systems & modernization. Every connector here is a bonus: each
  // agent is fully usable on pasted COBOL, JCL, ABAP, SQL or payment messages,
  // and the mainframe/payments/batch ones have no catalog server to offer at
  // all (there is no SDSF, SWIFT or Control-M entry in the curated 18).
  "cobol-to-java-modernizer": [
    { server: "github", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "gitlab", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
  ],
  "mainframe-abend-triage": NO_MCP,
  "legacy-pipeline-modernizer": [
    { server: "github", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    { server: "gitlab", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
  ],
  "monolith-decomposition-strategist": NO_MCP,
  "database-migration-translator": [{ server: "postgres", necessity: "optional", reasonKey: "reasonDatabase" }],
  "legacy-code-archaeologist": NO_MCP,
  "iso20022-payments-analyst": NO_MCP,
  "core-banking-batch-ops-copilot": NO_MCP,
  "banking-change-compliance-reviewer": [
    { server: "atlassian", necessity: "optional", reasonKey: "reasonIssueTracker" },
  ],
};

/** Console templates whose whole job runs through run_code: the agent's Code execution capability plus an E2B sandbox behind it. */
const E2B_REQUIRED = new Set(["data-analyst", "sales-sheet-analyst"]);

/**
 * Templates whose output is a draft the person copies out themselves. They sit
 * in a customer-facing category, which would otherwise ask for a channel - but
 * a channel binding delivers the agent's reply to the customer on its own, the
 * opposite of "draft only". The approval-gated, channel-connected version of
 * the WhatsApp drafter is a workflow template (workflowTemplates.ts).
 */
const DRAFT_ONLY = new Set(["whatsapp-reply-drafter"]);

/** True for a template that only drafts text for the person to send - it never sends and must not be put on a channel. */
export function isDraftOnlyTemplate(key: string | null | undefined): boolean {
  return typeof key === "string" && DRAFT_ONLY.has(key);
}

/** Personal/business templates whose entire job is answering from the tenant's own documents. Industry templates get this treatment by category instead - see prereqsForConsoleTemplate. */
const KNOWLEDGE_REQUIRED = new Set(["internal-kb-qa", "contract-policy-review", "hr-onboarding-assistant"]);

/**
 * Templates meant to answer real people on an inbound channel rather than only
 * inside the console, named one by one because they sit in categories that are
 * not wholly customer-facing.
 *
 * Every template in an "industry" category is channel-facing by definition and
 * is covered by category instead - listing them here as well would mean this
 * set had to be edited every time the gallery gains an industry template, which
 * it does regularly.
 */
const CHANNEL_FACING = new Set([
  "customer-support-triage",
  "multilingual-support-agent",
  "sales-lead-qualifier",
  "ecommerce-order-support",
  "ecommerce-product-recommender",
  "clinic-appointment-assistant",
  "patient-intake-assistant",
  "property-listing-assistant",
  "real-estate-lead-follow-up",
  "restaurant-reservation-assistant",
  "hotel-guest-services",
  "legal-intake-assistant",
  "logistics-dispatch-assistant",
]);

/** An industry template exists to answer that industry's customers, in their own language, on a channel they already use - and to answer from that business's own price list, policy wording or SOP. Both of those are derived from the category rather than re-listed per template. */
function isIndustryCategory(category: string | undefined): boolean {
  if (!category) return false;
  // Indexed by an arbitrary string, so the precise Record type has to be
  // widened - a literal-keyed object has no index signature (TS7053).
  return (CATEGORY_AUDIENCE as Record<string, string | undefined>)[category] === "industry";
}

/**
 * Prerequisites for one console-gallery template.
 *
 * `suggestKnowledgeBase` is read from the template's own record rather than
 * duplicated here - that flag is the source of truth for whether the gallery
 * already steers this template at the Knowledge page, and copying it would
 * just create two values that must agree.
 *
 * Two things are derived from the template's category rather than enumerated
 * per key, because the gallery gains industry templates faster than a hand-kept
 * list can track: an industry template is channel-facing, and an industry
 * template that wants a knowledge base *needs* one (grounded in that business's
 * own price list, policy wording or SOP, it is the whole value; ungrounded it
 * is a chat model with a costume on).
 *
 * A template key this file has never heard of therefore still gets an honest
 * answer - and never a guessed MCP server, because inventing a requirement is
 * worse than reporting none.
 */
export function prereqsForConsoleTemplate(
  key: string,
  template?: { suggestKnowledgeBase?: boolean; category?: string; deepResearchAvailable?: boolean }
): TemplatePrereqs {
  const suggestsKnowledge = Boolean(template?.suggestKnowledgeBase);
  const industry = isIndustryCategory(template?.category);
  const knowledge: KnowledgeNeed = !suggestsKnowledge
    ? KNOWLEDGE_REQUIRED.has(key)
      ? "required"
      : "none"
    : KNOWLEDGE_REQUIRED.has(key) || industry
      ? "required"
      : "recommended";
  return {
    knowledge,
    channel: !DRAFT_ONLY.has(key) && (CHANNEL_FACING.has(key) || industry || template?.category === "Customer & sales"),
    // No console template uses the native Google tools or code-intel - the
    // console gallery cannot enable either. E2B is the one exception
    // (E2B_REQUIRED above): the workbench switches Code execution on for
    // those templates after create (templateCapabilities.ts).
    google: false,
    e2b: E2B_REQUIRED.has(key),
    codeIntel: false,
    mcp: relaxForDeepResearch(key, CONSOLE_TEMPLATE_MCP[key] ?? NO_MCP, template?.deepResearchAvailable === true),
  };
}

/**
 * Template key -> the connector that built-in deep research makes unnecessary.
 * Deep research is off by default on a deployment, so the connector stays
 * REQUIRED unless the caller has confirmed (GET /api/research/status: enabled
 * and a search key configured) that this deployment offers it; unknown or a
 * failed check counts as "not offered".
 */
const DEEP_RESEARCH_COVERS: Readonly<Record<string, string>> = {
  "research-assistant": "brave-search",
};

function relaxForDeepResearch(
  key: string,
  suggestions: readonly McpSuggestion[],
  deepResearchAvailable: boolean
): readonly McpSuggestion[] {
  const covered = DEEP_RESEARCH_COVERS[key];
  if (!deepResearchAvailable || !covered) return suggestions;
  return suggestions.map((s) =>
    s.server === covered && s.necessity === "required" ? { ...s, necessity: "optional" as const } : s
  );
}

/** True for a template whose connector need depends on whether deep research is offered (the guide then asks the server). */
export function dependsOnDeepResearch(key: string): boolean {
  return Object.prototype.hasOwnProperty.call(DEEP_RESEARCH_COVERS, key);
}

/** How many connector slots this template's REQUIRED servers would consume. Interchangeable alternatives count once. */
export function requiredConnectorCount(prereqs: TemplatePrereqs): number {
  const groups = new Set<string>();
  let count = 0;
  for (const suggestion of prereqs.mcp) {
    if (suggestion.necessity !== "required") continue;
    if (suggestion.alternativeGroup) {
      if (groups.has(suggestion.alternativeGroup)) continue;
      groups.add(suggestion.alternativeGroup);
    }
    count += 1;
  }
  return count;
}

/**
 * Whether a FREE tenant can complete this template's setup at all.
 *
 * Only two ceilings here are real plan gates: the single knowledge collection
 * (V101 plan_limits) and the two catalog-only MCP connectors
 * (McpConnectorPlanLimits). Channels are NOT one of them - WhatsApp moved onto
 * FREE in V225, and Telegram/Email were always open - though every channel is
 * still bring-your-own account. Google Workspace and E2B are bring-your-own
 * credential, so no claim is made about a plan gate on either.
 */
export function freePlanFit(prereqs: TemplatePrereqs): { fits: boolean; connectorsNeeded: number } {
  const connectorsNeeded = requiredConnectorCount(prereqs);
  return { fits: connectorsNeeded <= (MCP_CONNECTOR_CAP.FREE ?? 0), connectorsNeeded };
}

// ---------------------------------------------------------------------------
// Platform gallery (control_plane.community_template, seeded by V163/V173/
// V219/V223 and served by core/templates). Keyed by a slug of the template
// name because those rows have generated UUIDs and no stable string key.
//
// NOT consumed by any page today: /templates renders those rows and lives
// outside this component's scope. It is recorded here so the mapping exists in
// one place when that page grows a guide of its own, and so the console and
// platform answers can never drift into two different opinions about, say,
// whether the Research agent needs brave-search.
//
// The 14 V173 industry templates are deliberately absent rather than listed as
// empty: each needs a knowledge collection and a WhatsApp/Telegram channel and
// no MCP server at all, which is exactly the default this file already
// produces, and re-stating it fourteen times would only create fourteen more
// rows to keep in sync.
// ---------------------------------------------------------------------------
export const PLATFORM_TEMPLATE_PREREQS: Record<string, TemplatePrereqs> = {
  // V163 - platform tuning archetypes.
  "faq-knowledge-base-bot": {
    knowledge: "required",
    channel: true,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "customer-support-agent": {
    knowledge: "required",
    channel: true,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "research-analysis-agent": {
    knowledge: "recommended",
    channel: false,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: [{ server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" }],
  },
  "financial-high-risk-agent": {
    knowledge: "required",
    channel: false,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },

  // V219 - marketing content engine. Only the lead agent benefits from search.
  "marketing-content-lead": {
    knowledge: "recommended",
    channel: false,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: [{ server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" }],
  },
  "marketing-long-form-writer": {
    knowledge: "recommended",
    channel: false,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "marketing-short-form-writer": {
    knowledge: "recommended",
    channel: false,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "marketing-caption-writer": {
    knowledge: "recommended",
    channel: false,
    google: false,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },

  // V223 - the templates that actually act. Every one of these ships a
  // non-empty allowedToolNames, which is a hard deny-everything-else: an MCP
  // server enabled on /tools reaches them ONLY if the exact
  // mcp__<server>__<tool> name is added to that agent's allow-list too.
  "inbox-triage-reply-drafter": {
    knowledge: "none",
    channel: false,
    google: true,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "meeting-scheduler-follow-up": {
    knowledge: "none",
    channel: false,
    google: true,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "invoice-expense-recorder": {
    knowledge: "none",
    channel: false,
    google: true,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "proposal-quote-writer": {
    knowledge: "required",
    channel: false,
    google: true,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "recruitment-screener": {
    knowledge: "none",
    channel: false,
    google: true,
    e2b: false,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "spreadsheet-data-analyst": {
    knowledge: "none",
    channel: false,
    google: false,
    e2b: true,
    codeIntel: false,
    mcp: NO_MCP,
  },
  "codebase-onboarding-guide": {
    knowledge: "none",
    channel: false,
    google: false,
    e2b: false,
    codeIntel: true,
    mcp: [
      { server: "github", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
      { server: "gitlab", necessity: "optional", reasonKey: "reasonRepo", alternativeGroup: "repo" },
    ],
  },
  "competitor-price-watch": {
    knowledge: "none",
    channel: false,
    google: true,
    e2b: false,
    codeIntel: false,
    mcp: [
      { server: "brave-search", necessity: "optional", reasonKey: "reasonWebSearch" },
      { server: "puppeteer", necessity: "optional", reasonKey: "reasonBrowseSite" },
    ],
  },
};
