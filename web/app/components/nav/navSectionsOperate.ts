import {
  TrendingUp,
  BadgeCheck,
  PlugZap,
  Banknote,
  ShieldAlert,
  ShieldCheck,
  Server,
  Zap,
  ListChecks,
  BarChart3,
  CreditCard,
  KeyRound,
  Sparkles,
  GraduationCap,
  FileText,
  FlaskConical,
  Gauge,
  AlertOctagon,
  Activity,
  ScrollText,
  Files,
  Palette,
  Puzzle,
  Plug,
  Radar,
  Gem,
  LifeBuoy,
  UserCog,
  Lock,
  Fingerprint,
  Wallet,
} from "lucide-react";

import type { NavSection } from "./navTypes";

// The operating half of the primary navigation (TEST & MONITOR, Plans, Help and
// ADMINISTRATION). Assembled with the authoring half into NAV_SECTIONS in
// navSections.ts, which documents the blueprint's conventions.

export const OPERATE_NAV_SECTIONS: NavSection[] = [
  // 3. TEST & MONITOR - pre-launch verification and post-launch observation in one
  // group (the tour's '3. Test' step). Gating is per ITEM: Playground is for everyone,
  // the /admin/* observability screens redirect a non-admin to '/', so they carry a
  // perm. No cross-workflow 'all runs' row: listWorkflowRuns() is per-workflow.
  {
    labelKey: "navGroupMonitor",
    cat: "var(--cat-analysis)",
    adminOnly: false,
    icon: Radar,
    items: [
      {
        href: "/playground",
        labelKey: "railPlay",
        icon: FlaskConical,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Evaluation, simulation and learning for agents (app/agents/quality).
      // The page has no scope gate of its own, so neither does the row.
      {
        href: "/agents/quality",
        labelKey: "railAgentQuality",
        icon: BadgeCheck,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Turn inspector (app/agents/turns, ADR-0021): read-only replay view of one journaled agent
      // turn. Scope VIEW_AUDIT_LOG, same as the journal endpoint it reads.
      {
        href: "/agents/turns",
        labelKey: "railTurnInspector",
        icon: ScrollText,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "VIEW_AUDIT_LOG",
      },
      {
        href: "/quick-task",
        labelKey: "railTask",
        icon: Zap,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Background agent runs (core/agents/.../job) - fire-and-forget jobs +
      // recurring schedules. Sits right after Quick Task deliberately: Quick
      // Task is "do this now while I watch", Jobs is the same ask without
      // the watching.
      {
        href: "/jobs",
        labelKey: "railJobs",
        icon: ListChecks,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Insights (app/insights): AI calls, success rate, estimated cost and job
      // status in one place. Not perm-gated on purpose - a member without
      // VIEW_USAGE still gets the job counts for their own agents; the page
      // itself hides the workspace-wide numbers on a 403.
      {
        href: "/insights",
        labelKey: "railInsights",
        icon: BarChart3,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Cost & ROI per agent (app/roi): spend, outcomes, budget and the pilot
      // report. Scope VIEW_USAGE, same as the cost data it reads. Not in PLANS:
      // that group is trimmed with billing off, ROI must stay reachable.
      {
        href: "/roi",
        labelKey: "railRoi",
        icon: TrendingUp,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "VIEW_USAGE",
      },
      // Usage meter (pricing audit B3). Lives in TEST & MONITOR, not PLANS, so it
      // survives with billing off (PLANS is emptied by filterBillingNav). Used-vs-limit for the
      // hosted-LLM-pool windows the platform already enforces server-side.
      // Not admin-gated: any member's chats consume the shared windows, so
      // any member should be able to see how much is left. The page itself
      // renders a BYOK notice when these caps don't apply to this tenant.
      {
        href: "/usage",
        labelKey: "railUsage",
        icon: Gauge,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Generated artifacts (images/audio/video, ingested documents): verifying what an
      // agent PRODUCED, so it sits after Playground. Not admin-gated (own files).
      {
        href: "/files",
        labelKey: "railFiles",
        icon: Files,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      {
        href: "/admin/issues",
        labelKey: "railIssues",
        icon: AlertOctagon,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "VIEW_AUDIT_LOG",
      },
      {
        href: "/admin/audit-tail",
        labelKey: "railActivity",
        icon: Activity,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "VIEW_AUDIT_LOG",
      },
      {
        href: "/admin/audit",
        labelKey: "railAudit",
        icon: ScrollText,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "VIEW_AUDIT_LOG",
      },
    ],
  },
  // Plans & billing: Plans = what your tier includes, Billing = invoices/payment.
  // Usage moved to TEST & MONITOR so this group can vanish cleanly when billing is off.
  {
    labelKey: "navGroupPlans",
    cat: "var(--cat-creative)",
    adminOnly: false,
    icon: Gem,
    items: [
      // Tenant-facing plan/feature comparison - what each tier unlocks and
      // what THIS tenant's plan currently includes (reads live entitlements
      // from GET /api/tenant/settings, so a platform-admin's plan-limits edit
      // is reflected here). Not admin-gated: any user can see the upgrade value.
      {
        href: "/features",
        labelKey: "railPlans",
        icon: Sparkles,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      {
        href: "/admin/billing",
        labelKey: "railBill",
        icon: CreditCard,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_BILLING",
      },
    ],
  },
  // Help & resources (2026-09-23 nav audit) - the learning/download/
  // transparency rows split out of the old "Plans & Help" grab-bag. All
  // visible to every member (none of these writes are admin-scoped).
  {
    labelKey: "navGroupResources",
    cat: "var(--cat-creative)",
    adminOnly: false,
    icon: LifeBuoy,
    items: [
      {
        href: "/help",
        labelKey: "railGuide",
        icon: GraduationCap,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // The Academy is a separate, proprietary deployable - no /learn index
      // lives in the OSS console. Public chrome (header/footer) links to the
      // hosted Academy directly; the tutor stays at /learn/tutor.
      // NAV-AUDIT FIX (2026-07-27): /help/docs existed but was only reachable
      // two clicks deep (AppShell -> /help -> "Open the docs" button) -
      // added a direct rail entry alongside Guide.
      {
        href: "/help/docs",
        labelKey: "railDocs",
        icon: FileText,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
      // Public plugin/app catalog (/plugins): in-house page with real store links where
      // published and honest 'not yet published' states otherwise (clients/PUBLISHING.md).
      // No Play Store or Wear OS link: neither exists publicly, a dead link is worse.
      {
        href: "/plugins",
        labelKey: "railDownloads",
        icon: Puzzle,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
      },
    ],
  },
  {
    labelKey: "navGroupAdmin",
    cat: "var(--cat-automation)",
    adminOnly: true,
    icon: ShieldCheck,
    items: [
      // /admin overview and /admin/maintenance stay ROLE-gated (adminOnly): full-admin
      // control panels. The rows below are per-scope; the section gates on hasAdminScope,
      // so a delegated user sees only the screens their grants cover.
      {
        href: "/admin",
        labelKey: "railAdmin",
        icon: ShieldCheck,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: true,
      },
      {
        href: "/admin/maintenance",
        labelKey: "railMaint",
        icon: Server,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: true,
      },
      // Team access (/admin/access, granular RBAC 2026-09-23) - the scoped
      // user-administration surface: member list, roles/permissions editor,
      // and the users-x-scopes access matrix.
      {
        href: "/admin/access",
        labelKey: "railAccess",
        icon: UserCog,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_USERS",
      },
      // Tenant REST API keys (/admin/api-keys, V483) - the vdak_ machine
      // credentials for headless /api/** callers (CI, partner systems).
      // Same MANAGE_USERS tier as Team access: a key carries PERM_* scopes,
      // so issuing one IS permission administration.
      {
        href: "/admin/api-keys",
        labelKey: "railApiKeys",
        icon: KeyRound,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_USERS",
      },
      // Tenant integrations (/admin/integrations) - Google Workspace
      // connection today. NAV AUDIT 2026-09-23: this page existed (scoped
      // MANAGE_INTEGRATIONS since the granular-RBAC pass) but had NO rail
      // entry anywhere - reachable only via the dormant AdminSubSidebar
      // config and the /admin overview's own links. Added here, directly
      // beside Developer API, the other MANAGE_INTEGRATIONS surface.
      {
        href: "/admin/integrations",
        labelKey: "railIntegrations",
        icon: Plug,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_INTEGRATIONS",
      },
      // Inbound MCP server access (core/mcpserver) - lets a tenant-admin
      // generate/rotate/revoke API keys for an external MCP client to connect
      // into this workspace and call curated agents as tools.
      {
        href: "/admin/mcp-servers",
        labelKey: "railMcp",
        icon: PlugZap,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_INTEGRATIONS",
      },
      // Workspace guardrail rules (core/guardrails). Admin-gated for the same
      // reason Billing and Branding are: the writes are ROLE_tenant-admin
      // server-side, so a member following this row would find only a 403.
      // The page's READS are open to everyone - a member who lands on it via a
      // direct link still sees what is being filtered, it just cannot edit.
      {
        href: "/admin/guardrails",
        labelKey: "railGuard",
        icon: ShieldAlert,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_GUARDRAILS",
      },
      // Every instruction the platform gives the model (core prompt directives), owner-editable.
      // Shown on the personal install too: it is not in PERSONAL_HIDDEN_ROUTES or EDITION_GATES.
      {
        href: "/admin/instructions",
        labelKey: "railInstructions",
        icon: ScrollText,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_TENANT_SETTINGS",
      },
      // White-label branding (/admin/branding) - moved here from the old
      // "Plans & Help" group in the 2026-09-23 nav audit: it is a
      // MANAGE_TENANT_SETTINGS write (ROLE_tenant-admin server-side), the
      // same class of surface as Guardrails, not a plan/billing concern.
      {
        href: "/admin/branding",
        labelKey: "railBrand",
        icon: Palette,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_TENANT_SETTINGS",
      },
      // Tenant-wide export/deletion (/admin/data-privacy) - the scoped home
      // for MANAGE_DATA_GOVERNANCE holders (the same section lives inside
      // /admin/maintenance for full admins, but that page is role-gated).
      {
        href: "/admin/data-privacy",
        labelKey: "railDataPrivacy",
        icon: Lock,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_DATA_GOVERNANCE",
      },
      // Per-tenant SSO connection + SCIM tokens (/admin/sso, TenantSsoController):
      // every endpoint there needs MANAGE_TENANT_SETTINGS.
      {
        href: "/admin/sso",
        labelKey: "railSso",
        icon: KeyRound,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_TENANT_SETTINGS",
      },
      // Agent trust (/admin/agent-trust, core/agents/trust+directory) -
      // delegation grants/tokens, the signed Know-Your-Agent manifest, and
      // public-directory publication for this tenant's agents. Same
      // MANAGE_AGENTS scope as /admin/payments: minting a token or
      // publishing a card IS managing the agent's authority surface.
      {
        href: "/admin/agent-trust",
        labelKey: "railAgentTrust",
        icon: Fingerprint,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_AGENTS",
      },
      // Agent commerce (/admin/commerce, core/payments/wallet+commerce,
      // core/agents-tools tools/x402) - agent wallets and ledgers, the AP2
      // intent->cart mandate chain, and x402 pay-per-call offers. Same
      // MANAGE_AGENTS scope as the payments console it complements.
      {
        href: "/admin/commerce",
        labelKey: "railCommerce",
        icon: Wallet,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_AGENTS",
      },
      // Agent payments console (app/admin/payments): mandates, spend,
      // transactions and the approval policy. Same MANAGE_AGENTS scope as commerce.
      {
        href: "/admin/payments",
        labelKey: "railPayments",
        icon: Banknote,
        exact: false,
        badge: null,
        badgeVariant: null,
        adminHidden: false,
        adminOnly: false,
        perm: "MANAGE_AGENTS",
      },
    ],
  },
];
