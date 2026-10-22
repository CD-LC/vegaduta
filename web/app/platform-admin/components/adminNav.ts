import {
  LayoutDashboard,
  Boxes,
  Wallet,
  BarChart3,
  ShieldCheck,
  Gauge,
  Siren,
  ScrollText,
  Users2,
  UserCog,
  Building2,
  CreditCard,
  Activity,
  Megaphone,
  FlaskConical,
  LayoutTemplate,
  Mic,
  Cpu,
  Puzzle,
  LockOpen,
  TrendingDown,
  Network,
  Settings2,
  Flag,
  HeartPulse,
  Star,
  Wrench,
  Radar,
  Construction,
  Timer,
  Database,
  MailX,
  ShieldAlert,
  Eye,
  type LucideIcon,
} from "lucide-react";
import { isPersonalEdition, isPersonalPlatformAdminAllowed } from "../../lib/edition";

// Platform-admin rail navigation (extracted from AdminShell). Groups trace an
// operator's mental model: who uses the platform (Tenancy), what powers it
// (Models & Tools), what it costs (Money), what can hurt it (Trust & Safety),
// what keeps it running (Operations), how we know it is healthy (Health &
// Audit) and what grows it (Growth). `queue` marks items with a live
// pending-count badge. Groups render collapsible; the group holding the
// current page is always open.

export type AdminNavItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  exact?: boolean;
  queue?: "mcp" | "templates" | "abuse";
  /** Extra path prefixes that also light this item up (merged sibling pages). */
  also?: string[];
};
export type AdminNavSection = { label: string | null; items: AdminNavItem[] };

export const NAV_SECTIONS: AdminNavSection[] = [
  {
    label: null,
    items: [{ href: "/platform-admin", label: "Overview", icon: LayoutDashboard, exact: true }],
  },
  {
    label: "Tenancy",
    items: [
      { href: "/platform-admin/tenants", label: "Tenants & Users", icon: Users2 },
      // Cross-tenant user directory - find, suspend, reset, re-role any user.
      { href: "/platform-admin/users", label: "Platform Users", icon: UserCog },
      // Per-tenant control page: status, plan, quota use, emergency stop/resume.
      { href: "/platform-admin/tenant-detail", label: "Tenant Detail", icon: Building2 },
      // One entry for the sandbox pair: the funnel view, with the legacy
      // per-visitor inventory + master-key-gated reclaim page
      // (/platform-admin/demo-sandboxes) linked from inside it.
      {
        href: "/platform-admin/demo-sandbox",
        label: "Demo Sandbox",
        icon: FlaskConical,
        also: ["/platform-admin/demo-sandboxes"],
      },
      { href: "/platform-admin/academy-learners", label: "Academy Learners", icon: LockOpen },
    ],
  },
  {
    label: "Models & Tools",
    items: [
      { href: "/platform-admin/llm-providers", label: "LLM & Providers", icon: Boxes },
      // Edge AI model catalog - curates the /api/edge/manifest served to the
      // consumer VegaDuta Edge surface; the on-device sibling of LLM &
      // Providers' hosted-model governance.
      { href: "/platform-admin/edge-models", label: "Edge AI Models", icon: Cpu },
      // First-party IDE/browser plugin distribution - artifact uploads + the
      // plugins.catalog setting behind the public /plugins marketplace page.
      { href: "/platform-admin/plugins", label: "IDE Plugins", icon: Puzzle },
      // Platform-admin credentials for tool-capability providers (ElevenLabs
      // voice, E2B, Brave, managed browser, Jev) - not chat providers.
      { href: "/platform-admin/tool-credentials", label: "Voice & Tool Credentials", icon: Mic },
      // The curated MCP server catalog every tenant browses - read-only view.
      { href: "/platform-admin/mcp-catalog", label: "MCP Catalog", icon: Network },
    ],
  },
  {
    label: "Money",
    items: [
      { href: "/platform-admin/budgets", label: "Budgets & Limits", icon: Wallet },
      { href: "/platform-admin/spend", label: "Spend & Reports", icon: BarChart3 },
      // Read-only cross-tenant subscriptions, comp grants and invoices.
      { href: "/platform-admin/billing", label: "Billing & Subscriptions", icon: CreditCard },
    ],
  },
  {
    label: "Trust & Safety",
    items: [
      { href: "/platform-admin/safety", label: "Safety & Moderation", icon: ShieldCheck },
      // Community template submissions - nothing is public until approved.
      { href: "/platform-admin/templates", label: "Community Templates", icon: LayoutTemplate, queue: "templates" },
      // STDIO MCP registrations needing platform-staff sign-off before spawn.
      { href: "/platform-admin/mcp-review", label: "MCP Review Queue", icon: ShieldCheck, queue: "mcp" },
      // In-app "Report AI content" submissions from every tenant (Play AI-content policy).
      { href: "/platform-admin/skill-security", label: "Skill Security Review", icon: ShieldAlert },
      { href: "/platform-admin/ai-reports", label: "AI Content Reports", icon: Flag },
      { href: "/platform-admin/abuse", label: "Abuse & Rate Control", icon: Gauge },
      // Live detector: risk scores, anomalies, throttles (AbuseSignalService).
      { href: "/platform-admin/fraud", label: "Fraud & Anomaly", icon: Siren, queue: "abuse" },
    ],
  },
  {
    label: "Operations",
    items: [
      // One-screen "is anything wrong right now" hub - every card links to
      // the page below that owns it.
      { href: "/platform-admin/ops", label: "Operations Hub", icon: Wrench },
      // Maintenance mode (master-key gated) + the tenant-app announcement banner.
      { href: "/platform-admin/maintenance", label: "Maintenance & Banner", icon: Construction },
      { href: "/platform-admin/jobs", label: "Scheduled Jobs", icon: Timer },
      { href: "/platform-admin/migrations", label: "Schema Migrations", icon: Database },
      { href: "/platform-admin/email-suppressions", label: "Email Suppressions", icon: MailX },
      // Every admin-editable platform_setting row in one searchable editor.
      { href: "/platform-admin/settings", label: "Platform Settings", icon: Settings2 },
    ],
  },
  {
    label: "Health & Audit",
    items: [
      // Langfuse traces (proxied by core) + LLM cost/latency + external tool links.
      { href: "/platform-admin/observability", label: "LLM Observability", icon: Radar },
      { href: "/platform-admin/diagnostics", label: "Diagnostics & Observability", icon: Activity },
      // Per-agent SLOs across every tenant - error rate, job success, eval
      // drift, spend anomalies, emergency-stop escalation.
      { href: "/platform-admin/fleet", label: "Fleet Health", icon: HeartPulse },
      { href: "/platform-admin/reliability", label: "Reliability & SLOs", icon: Gauge },
      { href: "/platform-admin/audit", label: "Audit & Compliance", icon: ScrollText },
      // Read-only "view as" sessions: active (end now) + history.
      { href: "/platform-admin/impersonation", label: "Impersonation", icon: Eye },
    ],
  },
  {
    label: "Growth",
    items: [
      // Headline platform-owner metrics: weekly active users, runs, success rate, time to first run.
      { href: "/platform-admin/north-star", label: "North Star", icon: Star },
      // Demo Sandbox -> Tenants is the far end of this funnel; /funnel shows
      // the top (landing clicks), demo-sandbox the middle.
      { href: "/platform-admin/funnel", label: "Landing Funnel", icon: TrendingDown },
      { href: "/platform-admin/newsletter", label: "Newsletter", icon: Megaphone },
    ],
  },
];

/**
 * The rail a personal install sees: only the destinations in PERSONAL_PLATFORM_ADMIN_ALLOW (LLM pools,
 * tool credentials, platform settings), so the Overview row goes too (the root redirects to the LLM
 * pools). Sections left empty drop out. Every other edition keeps the full rail.
 */
export function filterEditionNav<S extends { items: readonly { href: string }[] }>(
  sections: readonly S[],
  personal = isPersonalEdition(),
): S[] {
  if (!personal) return [...sections];
  return sections
    .map((s) => ({
      ...s,
      items: s.items.filter((i) => i.href !== "/platform-admin" && isPersonalPlatformAdminAllowed(i.href)),
    }))
    .filter((s) => s.items.length > 0);
}

/** Segment-boundary prefix match: "/platform-admin/demo-sandbox" must not
 *  light up on "/platform-admin/demo-sandboxes" unless listed in `also`. */
export function isNavItemActive(item: AdminNavItem, pathname: string | null | undefined): boolean {
  if (!pathname) return false;
  const hit = (href: string) => pathname === href || pathname.startsWith(`${href}/`);
  if (item.exact) return pathname === item.href;
  return hit(item.href) || (item.also ?? []).some(hit);
}

export function sectionHasActive(section: AdminNavSection, pathname: string | null | undefined): boolean {
  return section.items.some((i) => isNavItemActive(i, pathname));
}
