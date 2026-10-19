"use client";

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";
import {
  Activity,
  AlertOctagon,
  BadgeCheck,
  Blocks,
  BookMarked,
  BookOpen,
  BookUser,
  Bot,
  Brain,
  CheckCircle2,
  Clapperboard,
  ClipboardList,
  Code,
  Compass,
  Cpu,
  CreditCard,
  Database,
  FileText,
  Files,
  FlaskConical,
  FolderCode,
  Gauge,
  GitBranch,
  GraduationCap,
  History,
  Inbox,
  KeyRound,
  LayoutDashboard,
  LayoutTemplate,
  Lightbulb,
  ListChecks,
  Lock,
  Bell,
  Wallet,
  Network,
  Palette,
  Plug,
  Puzzle,
  Radio,
  ScrollText,
  Server,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  Target,
  UserCog,
  Users,
  Vault,
  Wand2,
  Workflow,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { cn } from "../../lib/utils";

// Route -> header glyph (icon audit, 2026-09-24). Mirrors the AppShell nav
// icon for the same destination so a page's header repeats the glyph the
// user just clicked in the rail — one vocabulary, not two. Longest-prefix
// match, so "/workflows/<id>/runs" resolves to the runs glyph, not the
// workflows one. Routes with no nav twin (ai-models, providers, tools/*)
// pick the closest meaning. A page can still pass `icon` explicitly, or
// `icon={null}` to render no tile.
const ROUTE_ICONS: Array<[string, LucideIcon]> = [
  ["/", LayoutDashboard],
  ["/agents", Bot],
  ["/agents/templates", LayoutTemplate],
  ["/agents/learning", Lightbulb],
  ["/agents/quality", BadgeCheck],
  ["/workflows", Workflow],
  ["/workflows/templates", LayoutTemplate],
  ["/workflows/*/runs", History],
  ["/templates", LayoutTemplate],
  ["/knowledge", BookOpen],
  ["/memory", Brain],
  ["/skills", BookMarked],
  ["/quick-task", Zap],
  ["/quick-task/history", History],
  ["/jobs", ListChecks],
  ["/studio", Clapperboard],
  ["/clips", Clapperboard],
  ["/shared-with-me", Users],
  ["/admin/channels", Radio],
  ["/admin/contacts", BookUser],
  ["/admin/leads", Target],
  ["/admin/inbox", Inbox],
  ["/tools", Blocks],
  ["/tools/code", Code],
  ["/tools/credentials", KeyRound],
  ["/tools/servers", Server],
  ["/secrets", Vault],
  ["/code-intel", GitBranch],
  ["/build", Wand2],
  ["/edge", Cpu],
  ["/code", FolderCode],
  ["/playground", FlaskConical],
  ["/files", Files],
  ["/admin/issues", AlertOctagon],
  ["/admin/audit-tail", Activity],
  ["/admin/audit", ScrollText],
  ["/features", Sparkles],
  ["/usage", Gauge],
  ["/admin/billing", CreditCard],
  ["/help", GraduationCap],
  ["/help/docs", FileText],
  ["/learn", Compass],
  ["/plugins", Puzzle],
  ["/data-usage", Database],
  ["/admin", ShieldCheck],
  ["/admin/maintenance", Server],
  ["/admin/access", UserCog],
  ["/admin/integrations", Plug],
  ["/admin/mcp-servers", KeyRound],
  ["/admin/guardrails", ShieldAlert],
  ["/admin/instructions", ScrollText],
  ["/admin/approvals", CheckCircle2],
  ["/admin/input-requests", ClipboardList],
  ["/admin/branding", Palette],
  ["/admin/data-privacy", Lock],
  ["/admin/sso", KeyRound],
  ["/admin/payments", Wallet],
  ["/notifications", Bell],
  ["/ai-models", Brain],
  ["/providers", Network],
];

// The /ui-kit previews render the REAL page components under a preview
// pathname (see ui-kit/console-preview/preview-harness.tsx), so map those
// back to the route they stand in for; otherwise the preview would show a
// header without its glyph and misreport the real page.
function normalisePreviewPath(pathname: string): string {
  if (pathname.startsWith("/ui-kit/dashboard-preview")) return "/";
  const m = pathname.match(/^\/ui-kit\/console-preview\/([^/]+)/);
  if (m) return "/" + m[1].replace("admin-", "admin/");
  return pathname;
}

function iconForPath(pathname: string | null): LucideIcon | null {
  if (!pathname) return null;
  const path = normalisePreviewPath(pathname).replace(/\/+$/, "") || "/";
  let best: LucideIcon | null = null;
  let bestLen = -1;
  for (const [pattern, icon] of ROUTE_ICONS) {
    const rx = new RegExp(
      "^" + pattern.split("*").map((s) => s.replace(/[.+?^${}()|[\]\\]/g, "\\$&")).join("[^/]+") + "(/|$)"
    );
    if (rx.test(path) && pattern.length > bestLen) {
      best = icon;
      bestLen = pattern.length;
    }
  }
  return best;
}

// Shared premium page header — the standard top-of-screen row for every
// authenticated console screen. Token-driven (theme-following), generous
// type scale, optional eyebrow + right-aligned actions. Backward-compatible:
// existing callers pass `title`/`subtitle`/`action`; new callers may pass
// `eyebrow`, `actions` and/or `icon`. `className` exists mainly so pages that
// stack content in a flex/grid `gap` column can zero the default bottom
// margin (`mb-0`) instead of paying margin + gap twice — the /admin
// extra-space bug.
//
// The icon tile lives INSIDE the <h1>, not as a sibling div: console.css
// styles `.ax-page-header > div:last-child > div` as a wrapping flex row for
// the actions slot, and when a page passes no actions the title block IS the
// last child — a sibling icon wrapper would inherit that rule.
export default function PageHeader({
  title,
  subtitle,
  eyebrow,
  icon,
  action = null,
  actions = null,
  className,
  as: Heading = "h1",
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  eyebrow?: ReactNode;
  // Explicit glyph. Omit to resolve from the current route; `null` hides it.
  icon?: LucideIcon | null;
  action?: ReactNode;
  actions?: ReactNode;
  className?: string;
  // "h2" when the page shell already supplies the page-level h1 (one h1 per page).
  as?: "h1" | "h2";
}) {
  const pathname = usePathname();
  const Icon = icon === undefined ? iconForPath(pathname) : icon;
  return (
    <div
      className={cn(
        "ax-page-header mb-6 flex flex-col gap-4 border-b border-border pb-5 sm:flex-row sm:items-end sm:justify-between",
        className
      )}
    >
      <div className="space-y-1.5">
        {eyebrow && (
          <div className="flex items-center gap-2 font-mono text-xs font-semibold uppercase tracking-[0.16em] text-primary">
            {/* Gradient keyline dash (aurora depth layer, 2026-08-12) -
                decorative wayfinding mark, hidden from AT. */}
            <span
              aria-hidden="true"
              className="h-[3px] w-5 shrink-0 rounded-full"
              style={{ background: "var(--accent-gradient)" }}
            />
            {eyebrow}
          </div>
        )}
        <Heading className="flex items-center gap-3 text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
          {Icon && (
            <span
              aria-hidden="true"
              className="flex size-10 shrink-0 items-center justify-center rounded-xl border border-primary/15 bg-primary/10 text-primary"
            >
              <Icon className="size-5" strokeWidth={2} />
            </span>
          )}
          <span className="min-w-0 [text-wrap:balance]">{title}</span>
        </Heading>
        {subtitle && <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">{subtitle}</p>}
      </div>
      {(actions || action) && (
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          {actions}
          {action}
        </div>
      )}
    </div>
  );
}
