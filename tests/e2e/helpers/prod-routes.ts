/**
 * The production route surface, split by how a LOGGED-OUT visitor is
 * actually treated. These lists were pinned by probing the deployed
 * the deployed app (not by reading the source tree) - prod can run an older
 * image than repo HEAD, so deployed behaviour is the only safe source of
 * truth for assertions.
 */

export interface PublicPage {
  path: string;
  /** A distinctive string that must appear in <title>. */
  title: RegExp;
  /** A distinctive string that must appear in the rendered <h1>. */
  h1: RegExp;
}

/**
 * Fully public, server-rendered pages. Each has its OWN title and h1 - the
 * pair is what proves the route really rendered its own content rather than
 * falling through to the landing page (the failure mode the 2026-07-30
 * content-onpage SEO audit found on /help and /features).
 */
export const PUBLIC_PAGES: PublicPage[] = [
  { path: "/", title: /VegaD/i, h1: /Eyes\. Ears\. Voice\. Hands\./i },
  { path: "/billing", title: /free|open source/i, h1: /open source|free for everyone|everything is free/i },
  { path: "/templates", title: /Community Templates/i, h1: /community has built/i },
  { path: "/plugins", title: /Plugin|Extension|IDE/i, h1: /.+/ },
  { path: "/ai", title: /AI Capabilities/i, h1: /AI stack behind every VegaD/i },
  { path: "/integrations", title: /Integrations/i, h1: /Every channel and service/i },
  { path: "/platform", title: /Platform/i, h1: /.+/ },
  { path: "/tutorials", title: /Tutorial|Guide/i, h1: /.+/ },
  { path: "/compare", title: /Compare|Alternative/i, h1: /.+/ },
  { path: "/why-vegaduta", title: /Why/i, h1: /.+/ },
  { path: "/hire", title: /Hire|Papers|CV/i, h1: /.+/ },
  { path: "/earn", title: /Earn|Partner|Affiliate/i, h1: /.+/ },
  { path: "/about", title: /About/i, h1: /.+/ },
  { path: "/docs", title: /Doc/i, h1: /.+/ },
  { path: "/help", title: /Help/i, h1: /.+/ },
  { path: "/features", title: /Feature/i, h1: /.+/ },
  { path: "/terms", title: /Terms/i, h1: /.+/ },
  { path: "/privacy", title: /Privacy/i, h1: /.+/ },
  { path: "/signup", title: /Create Your Free VegaD/i, h1: /.+/ },
  { path: "/login", title: /.+/, h1: /.+/ },
  { path: "/try", title: /.+/, h1: /.+/ },
  { path: "/whatsapp-cost-calculator", title: /WhatsApp/i, h1: /.+/ },
  { path: "/help/workflow-recipes", title: /Recipe|Workflow/i, h1: /.+/ },
];

/**
 * The four SEO/AEO hubs' detail pages (2026-08-14 architecture) plus the
 * comparison pages. Sampled rather than exhaustive - the sitemap spec
 * separately asserts EVERY advertised URL resolves.
 */
export const DETAIL_PAGES: string[] = [
  "/compare/n8n-alternative-india",
  "/compare/dify-alternative-india",
  "/compare/botpress-alternative-india",
  "/ai/model-context-protocol",
];

/** Industry recipe pages - a representative sample across sectors. */
export const RECIPE_INDUSTRIES: string[] = [
  "healthcare",
  "ecommerce",
  "logistics",
  "real-estate",
  "education",
  "legal",
  "manufacturing",
  "call-center",
];

/**
 * Console routes that REQUIRE auth. On prod these do NOT 401 or redirect -
 * AuthProvider renders GenesisExperience (the marketing landing) in place of
 * the console for an unauthenticated visitor, leaving the URL untouched. The
 * security property worth testing is therefore "no tenant data is rendered",
 * not "the request was rejected".
 */
export const GATED_CONSOLE_ROUTES: string[] = [
  "/agents",
  "/agents/learning",
  "/agents/quality",
  "/agents/templates",
  "/workflows",
  "/workflows/templates",
  "/knowledge",
  "/playground",
  "/studio",
  "/usage",
  "/devices",
  "/files",
  "/jobs",
  "/clips",
  "/edge",
  "/providers",
  "/ai-models",
  "/quick-task",
  "/quick-task/history",
  "/tools",
  "/tools/code",
  "/tools/credentials",
  "/tools/servers",
  "/code-intel",
  "/build",
  "/build/github",
  "/shared-with-me",
  "/data-usage",
  "/help/docs",
  "/admin",
  "/admin/users",
  "/admin/billing",
  "/admin/channels",
  "/admin/audit",
  "/admin/audit-tail",
  "/admin/inbox",
  "/admin/approvals",
  "/admin/integrations",
  "/admin/mcp-servers",
  "/admin/issues",
  "/admin/maintenance",
];

/**
 * Text fragments that must NEVER appear on a page rendered for a logged-out
 * visitor. These are console-only chrome/strings - if any shows up on a
 * gated route without a session, the auth gate has failed open.
 */
export const CONSOLE_ONLY_MARKERS: RegExp[] = [
  /agents used/i,
  /Save draft/i,
  /Sign out/i,
  /Workspace settings/i,
];

/** Platform-admin console routes (admin.<E2E_DOMAIN>). */
export const ADMIN_ROUTES: string[] = [
  "/",
  "/tenants",
  "/llm-providers",
  "/spend",
  "/budgets",
  "/audit",
  "/abuse",
  "/fraud",
  "/safety",
  "/templates",
  "/mcp-catalog",
  "/mcp-review",
  "/plugins",
  "/edge-models",
  "/tool-credentials",
  "/academy-learners",
  "/newsletter",
  "/demo-sandbox",
  "/diagnostics",
];
