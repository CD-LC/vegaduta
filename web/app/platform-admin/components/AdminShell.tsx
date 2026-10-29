"use client";

import { useEffect, useState, type ReactNode } from "react";
import { motion } from "motion/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { Power, Moon, Sun, Menu, X, CheckCircle2, ChevronDown, Siren } from "lucide-react";
import { useAuth } from "./PlatformAdminGate";
import { api, prefetchApiGets } from "../lib/api";
import BrandIdentityModal from "./BrandIdentityModal";
import NotificationBell from "./NotificationBell";
import { Button } from "./ui/button";
import { filterBillingNav } from "../lib/billing-flag";
import { useConfirm } from "./admin/ConfirmDialog";
import { NAV_SECTIONS, filterEditionNav, isNavItemActive, sectionHasActive, type AdminNavItem } from "./adminNav";
import { isPersonalEdition } from "../../lib/edition";
import { NAV_COLLAPSED_KEY, parseCollapsed, serializeCollapsed } from "./navCollapsed";

// Governance & Operations Console IA (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md
// §3 + §10) - the admin console is organized into ten sections so an admin
// finds any control in <=2 clicks. The old ad-hoc screens (Ecosystem
// Maintenance / Subscription Plans / Observability / Scholarships) were split
// out and re-homed into the section each belongs to; sections whose backend
// endpoints are still being built (core-3, roadmap §9) render a labeled
// "coming soon" state with the endpoint contract rather than break.
//
// Chrome is unchanged from the 2026-07-22 rebuild: the same modern, labeled
// shadcn-token sidebar the tenant console (web/app/components/AppShell.tsx)
// uses, so the two apps read as one product. Theme is local (data-mode on
// <html> + a body class). The old Subscription Plans screen (tenant roster +
// plan-tier override + plan-limits editor) folded into Tenants & Users.
// The Scholarships screen was removed outright (2026-09) when the Fast-Track
// Scholarship program was retired.
// Grouped, section-labelled nav - the same Linear/Vercel/Stripe pattern the
// tenant console's AppShell uses, applied here so the two apps read as one
// product. The groups trace an operator's mental model: who uses the platform
// (TENANCY), what powers it (MODELS & TOOLS), what it costs (MONEY), what can
// hurt it (TRUST & SAFETY), what keeps it running (OPERATIONS) and what grows
// it (GROWTH). `queue` marks items with a live pending-count badge.
function initialsOf(name: string) {
  if (!name) return "?";
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] || "") + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase() || "?";
}

// Session boot warm-up guard - AdminShell remounts on every navigation, but
// the prefetch below should fire once per signed-in identity, not once per
// page. Keyed on the username (not a boolean) so a user switch without a
// full reload re-warms for the new identity (whose cached reads the 401
// flush on its last call already cleared anyway).
let prefetchedFor: string | null = null;

// Reads that feed the rail's queue badges and the overview's spend tile. Their controllers (spend report,
// MCP review, abuse monitor, template moderation) are operator tooling a personal install does not run,
// so the shell does not ask for them there; it would only collect 404s.
const OPERATOR_ONLY_PREFETCH: ReadonlySet<string> = new Set([
  "/api/platform/spend/summary",
  "/api/platform/mcp-review/pending",
  "/api/platform/abuse-monitor/throttled",
]);

export default function AdminShell({
  title,
  eyebrow = "Platform Admin",
  actions = null,
  children,
}: {
  title?: string;
  eyebrow?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const askConfirm = useConfirm();
  const { username, logout } = useAuth();
  const pathname = usePathname();
  // Personal edition: one owner, no fleet - no operator queues to count (see OPERATOR_ONLY_PREFETCH).
  const personal = isPersonalEdition();
  // Light-first (2026-09-26): the console now defaults to light to match the
  // tenant console's default look; the dark/light toggle persists per-user.
  const [mode, setMode] = useState("light");
  const [brandOpen, setBrandOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  // Live queue badges on the rail (pending MCP reviews / template
  // submissions / throttled principals) - one deduped GET each, refreshed by
  // the apiFetch SWR cache on navigation.
  const [queueCounts, setQueueCounts] = useState<{
    mcp: number | null;
    templates: number | null;
    abuse: number | null;
  }>({
    mcp: null,
    templates: null,
    abuse: null,
  });
  // Platform kill-switch state - the rail-level "PLATFORM STOPPED" banner is
  // the one signal that must be visible from EVERY page, not only /audit.
  const [killSwitch, setKillSwitch] = useState<any>(null);
  const [resuming, setResuming] = useState(false);

  // Restore the last theme choice on mount. AdminShell is instantiated fresh
  // by every page.js (there's no shared layout/context holding this), so
  // without reading it back here the toggle would reset to the light default
  // on every navigation. Starts at "light" (matching layout.tsx's
  // server-rendered data-mode="light") and only overwrites client-side,
  // post-mount, so there's no hydration mismatch.
  useEffect(() => {
    const saved = window.localStorage.getItem("vd-admin-theme");
    if (saved === "light" || saved === "dark") setMode(saved);
  }, []);

  useEffect(() => {
    document.documentElement.setAttribute("data-mode", mode);
    document.body.classList.toggle("dark", mode === "dark");
    document.body.classList.toggle("light", mode === "light");
    window.localStorage.setItem("vd-admin-theme", mode);
  }, [mode]);

  useEffect(() => {
    setMobileNavOpen(false);
  }, [pathname]);

  // Below `lg`, the sidebar is hidden purely via a transform (translate-x),
  // which leaves it and every link/control inside it focusable while
  // invisible off-screen. `inert` (below) removes it from the tab order and
  // from assistive tech when closed on narrow viewports - but the sidebar is
  // ALWAYS visible at `lg` and up regardless of mobileNavOpen (that state
  // only matters below `lg`), so inert-ing it there would break desktop nav
  // entirely. Track the `lg` breakpoint in JS so inert is only ever applied
  // when the sidebar is actually hidden.
  const [isDesktopNav, setIsDesktopNav] = useState(true);
  useEffect(() => {
    const mq = window.matchMedia("(min-width: 1024px)");
    setIsDesktopNav(mq.matches);
    const onChange = (e: MediaQueryListEvent) => setIsDesktopNav(e.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  // Escape closes the mobile nav, same as every hand-rolled dialog in this
  // app; only wired below `lg` since that's the only state where it's open
  // as an overlay rather than a permanent sidebar.
  useEffect(() => {
    if (isDesktopNav || !mobileNavOpen) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setMobileNavOpen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [isDesktopNav, mobileNavOpen]);

  const [coreHealthy, setCoreHealthy] = useState(true);
  useEffect(() => {
    let cancelled = false;
    api
      .getPlatformHealth()
      .then(() => !cancelled && setCoreHealthy(true))
      .catch(() => !cancelled && setCoreHealthy(false));
    return () => {
      cancelled = true;
    };
  }, []);

  // Queue badges + kill-switch status: three cheap GETs piggybacking the
  // shared GET cache - failures just leave the badge hidden, never crash nav.
  useEffect(() => {
    let cancelled = false;
    const none = Promise.resolve<null>(null);
    Promise.all([
      personal ? none : api.listPendingMcpReviews().catch((): null => null),
      personal ? none : api.getAdminTemplatesPendingQuiet().catch((): null => null),
      personal ? none : api.getAbuseThrottled().catch((): null => null),
      api.getKillSwitchStatus().catch((): null => null),
    ]).then(
      ([mcp, templates, abuse, ks]) => {
        if (cancelled) return;
        setQueueCounts({
          mcp: Array.isArray(mcp) ? mcp.length : null,
          templates: Array.isArray(templates) ? templates.length : null,
          abuse: Array.isArray(abuse) ? abuse.length : null,
        });
        setKillSwitch(ks);
      },
      () => {}
    );
    return () => {
      cancelled = true;
    };
  }, [personal]);

  async function handleResumePlatform() {
    if (!(await askConfirm("Resume the platform? Every tenant's requests will flow again immediately."))) return;
    setResuming(true);
    try {
      const status = await api.resumePlatform();
      setKillSwitch(status);
    } catch {
      // toJson already toasted the reason.
    } finally {
      setResuming(false);
    }
  }

  // Session boot warm-up (see prefetchApiGets' comment in lib/api.ts): the
  // reads the overview + nav chrome need, fired once per identity at first
  // AdminShell mount - so the first navigation renders from the SWR cache
  // rather than paying a cold ~500ms round trip per read. A page mounting
  // mid-prefetch shares the in-flight request via apiFetch's dedupe.
  useEffect(() => {
    if (!username || prefetchedFor === username) return;
    prefetchedFor = username;
    prefetchApiGets(
      [
        "/api/platform/health",
        "/api/platform/issues/open-count",
        "/api/platform/spend/summary",
        "/api/platform/tenant-counts",
        "/api/system/status",
        // Rail badge + kill-switch banner reads
        "/api/platform/mcp-review/pending",
        "/api/platform/abuse-monitor/throttled",
        "/api/platform/kill-switch",
      ].filter((u) => !personal || !OPERATOR_ONLY_PREFETCH.has(u))
    );
  }, [username, personal]);

  function isActive(item: AdminNavItem) {
    return isNavItemActive(item, pathname);
  }

  // Collapsible rail groups. The group holding the current page is always
  // open; the rest remember the operator's choice per browser.
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(NAV_COLLAPSED_KEY);
      if (raw) setCollapsed(parseCollapsed(raw));
    } catch {
      // private mode / corrupt value: all groups open.
    }
  }, []);
  function toggleGroup(label: string) {
    setCollapsed((prev) => {
      const next = { ...prev, [label]: !prev[label] };
      try {
        window.localStorage.setItem(NAV_COLLAPSED_KEY, serializeCollapsed(next));
      } catch {
        // not persisted; still toggles for this visit.
      }
      return next;
    });
  }

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      {mobileNavOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/50 backdrop-blur-sm lg:hidden"
          onClick={() => setMobileNavOpen(false)}
          aria-hidden="true"
        />
      )}

      {/* Sidebar */}
      <nav
        aria-label="Platform admin sections"
        className={`fixed inset-y-0 start-0 z-50 flex w-56 flex-col border-e border-border bg-card/60 backdrop-blur-xl transition-transform duration-300 ease-out lg:sticky lg:top-0 lg:h-dvh lg:translate-x-0 ${
          mobileNavOpen ? "translate-x-0" : "ltr:-translate-x-full rtl:translate-x-full"
        }`}
        // `inert` isn't in this React version's HTML attribute types (it
        // predates React's own typings for it) but is a real, well-supported
        // DOM attribute - spread as `any` to set it without a type error.
        {...({ inert: !isDesktopNav && !mobileNavOpen ? true : undefined } as any)}
      >
        {/* Brand — the winged human-in-helmet emblem, ALWAYS (direct
            instruction 2026-07-25, mirrored from the tenant console: "our
            brand logo is always this one, not just V"), in its own colors,
            height-fixed with width free. The wordmark carries the shared
            `brand-name` class (Yatra One face, white/black "Vega" + orange
            "Dūta", same as web / the Academy / the Keycloak login). */}
        <div className="flex h-16 shrink-0 items-center gap-2.5 border-b border-border px-4">
          <button
            type="button"
            aria-label="VegaDūta brand identity"
            onClick={() => setBrandOpen(true)}
            className="flex h-9 shrink-0 items-center transition-opacity hover:opacity-80"
          >
            <img
              src="/logo-mark.png"
              alt="VegaDūta"
              // width/height attrs (real artwork ratio, 1024x753 - see
              // Logo.tsx's own comment) let the browser reserve this box
              // before the image loads instead of shifting layout once it
              // arrives; className's h-8/w-auto still control the rendered
              // size.
              width={44}
              height={32}
              className="h-8 w-auto shrink-0 object-contain"
              draggable={false}
            />
          </button>
          <Link href="/platform-admin" className="brand-name text-lg font-semibold tracking-tight">
            Vega<span className="text-primary">Dūta</span>
          </Link>
        </div>

        {/* Destinations - grouped sections, same pattern as the tenant
            console's rail: a group header is the cheapest way to keep a long
            nav scannable as destinations grow. */}
        <div className="flex-1 overflow-y-auto px-3 pb-3 pt-3">
          <div className="px-3 pb-1.5 pt-1 text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground select-none">
            {eyebrow}
          </div>
          {filterEditionNav(filterBillingNav(NAV_SECTIONS), personal).map((section, i) => {
            const groupId = (section.label ?? "home").toLowerCase().replace(/[^a-z0-9]+/g, "-");
            const hasActive = sectionHasActive(section, pathname);
            const open = !section.label || hasActive || !collapsed[section.label];
            return (
              <div key={section.label ?? "home"} className={i === 0 ? "" : "mt-4"}>
                {section.label && (
                  <button
                    type="button"
                    onClick={() => toggleGroup(section.label as string)}
                    aria-expanded={open}
                    aria-controls={`admin-nav-${groupId}`}
                    disabled={hasActive}
                    className="flex w-full items-center justify-between rounded-md border-0 bg-transparent px-3 pb-1 pt-1 text-start text-xs font-semibold uppercase tracking-[0.16em] text-muted-foreground shadow-none select-none hover:text-foreground disabled:cursor-default disabled:hover:text-muted-foreground"
                  >
                    {section.label}
                    <ChevronDown
                      size={13}
                      aria-hidden="true"
                      className={`shrink-0 transition-transform ${open ? "" : "-rotate-90"}`}
                    />
                  </button>
                )}
                <div id={`admin-nav-${groupId}`} className="space-y-0.5" hidden={!open}>
                  {section.items.map((item) => {
                    const Icon = item.icon;
                    const active = isActive(item);
                    const count = item.queue ? queueCounts[item.queue as keyof typeof queueCounts] : null;
                    return (
                      <Link
                        key={item.href}
                        href={item.href}
                        aria-current={active ? "page" : undefined}
                        onClick={() => setMobileNavOpen(false)}
                        className={`group relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                          active ? "text-foreground" : "text-muted-foreground hover:bg-accent hover:text-foreground"
                        }`}
                      >
                        {active && (
                          <>
                            <motion.span
                              layoutId="admin-rail-indicator"
                              className="absolute inset-0 -z-10 rounded-lg border border-primary/20 bg-primary/10"
                              transition={{ type: "spring", stiffness: 420, damping: 34 }}
                            />
                            <motion.span
                              layoutId="admin-rail-accent"
                              className="absolute start-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-e-full bg-primary"
                              transition={{ type: "spring", stiffness: 420, damping: 34 }}
                            />
                          </>
                        )}
                        <Icon
                          size={18}
                          strokeWidth={2.1}
                          aria-hidden="true"
                          className={`shrink-0 ${active ? "text-primary" : "text-muted-foreground transition-colors group-hover:text-foreground"}`}
                        />
                        <span className="min-w-0 flex-1 truncate">{item.label}</span>
                        {count != null && count > 0 && (
                          <span
                            className="ms-auto inline-flex h-4 min-w-4 items-center justify-center rounded-full bg-[var(--warning-bg)] px-1 font-mono text-xs font-semibold text-[var(--warning-text)]"
                            title={`${count} pending`}
                          >
                            {count}
                          </span>
                        )}
                      </Link>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>

        {/* Footer: account */}
        <div className="shrink-0 border-t border-border p-3">
          <div className="flex items-center gap-1.5">
            <div className="flex min-w-0 flex-1 items-center gap-2.5 px-2 py-1.5">
              <div
                className="flex size-8 shrink-0 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary"
                aria-hidden="true"
              >
                {initialsOf(username)}
              </div>
              <div className="min-w-0 flex-1 truncate text-sm font-medium leading-tight" title={username || undefined}>
                {username || "—"}
              </div>
            </div>
            <button
              type="button"
              onClick={logout}
              title="Sign out"
              aria-label="Sign out"
              className="flex size-8 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
            >
              <Power size={15} />
            </button>
          </div>
        </div>
      </nav>

      {/* Main */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex min-h-16 flex-wrap items-center gap-x-3 gap-y-2 border-b border-border bg-background/70 px-4 py-2.5 backdrop-blur-xl sm:px-6">
          <button
            type="button"
            onClick={() => setMobileNavOpen((o) => !o)}
            aria-label={mobileNavOpen ? "Close menu" : "Open menu"}
            aria-expanded={mobileNavOpen}
            className="flex size-10 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground lg:hidden"
          >
            {mobileNavOpen ? <X size={20} /> : <Menu size={20} />}
          </button>

          <span className="hidden shrink-0 whitespace-nowrap font-mono text-xs uppercase tracking-[0.14em] text-muted-foreground sm:inline-block">
            {eyebrow}
          </span>
          {title && (
            <h1 className="min-w-0 truncate text-sm font-semibold text-foreground" title={title}>
              {title}
            </h1>
          )}

          <div className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-2">
            {actions}

            <NotificationBell />

            <div
              className="flex items-center gap-0.5 rounded-md border border-border bg-secondary/40 p-0.5"
              role="group"
              aria-label="Theme"
            >
              <button
                type="button"
                onClick={() => setMode("dark")}
                aria-pressed={mode === "dark"}
                aria-label="Dark theme"
                className={`flex size-7 items-center justify-center rounded border-0 bg-transparent p-0 transition-colors ${mode === "dark" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
              >
                <Moon size={13} />
              </button>
              <button
                type="button"
                onClick={() => setMode("light")}
                aria-pressed={mode === "light"}
                aria-label="Light theme"
                className={`flex size-7 items-center justify-center rounded border-0 bg-transparent p-0 transition-colors ${mode === "light" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"}`}
              >
                <Sun size={13} />
              </button>
            </div>

            <div
              className={`hidden items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium sm:inline-flex ${
                coreHealthy
                  ? "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
                  : "border-destructive/30 bg-destructive/10 text-destructive"
              }`}
            >
              <span
                className={`size-1.5 rounded-full ${coreHealthy ? "bg-[var(--state-success)]" : "bg-destructive"}`}
                aria-hidden="true"
              />
              {coreHealthy ? "PLATFORM HEALTHY" : "PLATFORM OFFLINE"}
            </div>
          </div>
        </header>

        {/* Platform-wide kill switch banner - visible on EVERY page while the
            platform is stopped, since "tenant traffic is dead right now" is
            context no admin should ever have to go looking for. */}
        {killSwitch?.stopped && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-destructive/30 bg-destructive/10 px-4 py-2.5 sm:px-6">
            <div className="flex min-w-0 items-center gap-2 text-sm font-medium text-destructive">
              <Siren size={15} className="shrink-0" aria-hidden="true" />
              <span className="truncate">
                PLATFORM STOPPED{killSwitch.reason ? ` — ${killSwitch.reason}` : ""}
                {killSwitch.stoppedAt ? ` · since ${new Date(killSwitch.stoppedAt).toLocaleString()}` : ""}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <Link
                href="/platform-admin/audit"
                className="text-xs font-medium text-destructive underline underline-offset-2 hover:opacity-80"
              >
                Details
              </Link>
              <Button size="sm" onClick={handleResumePlatform} disabled={resuming}>
                <CheckCircle2 size={12} /> {resuming ? "Resuming…" : "Resume platform"}
              </Button>
            </div>
          </div>
        )}

        <motion.main
          key={pathname}
          className="flex-1 px-4 py-6 sm:px-6 lg:px-8"
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
        >
          <div className="mx-auto w-full max-w-6xl">{children}</div>
        </motion.main>
      </div>

      <BrandIdentityModal open={brandOpen} onClose={() => setBrandOpen(false)} />
    </div>
  );
}
