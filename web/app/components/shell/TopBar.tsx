"use client";

// The sticky topbar: title slot, org chip, env pill, page actions, usage, bell, theme/language cluster, trust strip. Lifted out of AppShell.tsx unchanged.

import { Moon, Sun, Menu, X } from "lucide-react";
import { useAuth } from "../AuthProvider";
import { useTranslations } from "../../lib/i18n";
import { useTheme } from "../ThemeProvider";
import TourReopenButton from "../tour/TourReopenButton";
import PageGuideButton from "../pageguide/PageGuideButton";
import TwoFactorButton from "../TwoFactorButton";
import LanguageSwitcher from "../LanguageSwitcher";
import { UsageMeterPill } from "../UsageQuota";
import { useAppChrome } from "../AppChromeProvider";
import EdgeSetupChip from "../edge/EdgeSetupChip";
import NotificationBell from "../NotificationBell";
import TrustStrip from "../TrustStrip";
import { getPublicEnv } from "../../lib/env";
import type { ComponentProps, ReactNode, RefObject } from "react";
import { initialsOf } from "./initialsOf";

export default function TopBar({
  headerRef,
  mobileMenuButtonRef,
  mobileNavOpen,
  onToggleMobileNav,
  title,
  eyebrow,
  actions,
  profileHref,
  coreHealthy,
  status,
}: {
  headerRef: RefObject<HTMLElement | null>;
  mobileMenuButtonRef: RefObject<HTMLButtonElement | null>;
  mobileNavOpen: boolean;
  onToggleMobileNav: () => void;
  title?: ReactNode;
  eyebrow?: ReactNode;
  actions?: ReactNode;
  profileHref: string | null;
  coreHealthy: boolean;
  status: ComponentProps<typeof TrustStrip>["status"];
}) {
  const tNav = useTranslations("nav");
  const { isAdmin } = useAuth();
  const { resolvedMode, setMode } = useTheme();
  const { tenantSettings, usageQuota } = useAppChrome();
  return (
    <header
      ref={headerRef}
      // Toasts stack BELOW this bar, never on its buttons (lib/toastPlacement.ts).
      data-toast-avoid=""
      // PRODUCT-TOUR ANCHOR: the "topbar" step spotlights this whole strip
      // while it names the controls inside it (page guide, alerts, theme,
      // language, the ? menu). Present on every console route.
      data-tour="topbar"
      className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-[var(--surface-border)] px-4 backdrop-blur-xl sm:px-6"
      style={{ backgroundColor: "color-mix(in srgb, var(--surface-card) 90%, transparent)" }}
    >
      <button
        ref={mobileMenuButtonRef}
        type="button"
        onClick={onToggleMobileNav}
        aria-label={tNav(mobileNavOpen ? "closeMenuAriaLabel" : "openMenuAriaLabel")}
        aria-expanded={mobileNavOpen}
        className="flex size-9 items-center justify-center rounded-md border-0 bg-transparent p-0 text-[var(--text-muted)] shadow-none transition-colors hover:bg-[var(--surface-raised)] hover:text-[var(--text-primary)] lg:hidden"
      >
        {mobileNavOpen ? <X className="size-5" aria-hidden="true" /> : <Menu className="size-5" aria-hidden="true" />}
      </button>

      {/* Organization identity — the tenant's org name printed at the top
            of every console screen (direct feedback: "print the org name at
            top in the header"). Falls back silently until tenant settings
            resolve, so it never blocks or flashes an empty chip. */}
      {eyebrow && (
        <span className="hidden font-mono text-xs uppercase tracking-[0.14em] text-muted-foreground sm:inline">
          {eyebrow}
        </span>
      )}
      {/* Real, single page-level H1 — visually hidden (Playwright heading
            assertions + a11y/SEO); pages that render their own <h1> don't
            pass `title`, so no duplicate heading. */}
      {title && <h1 className="sr-only">{title}</h1>}

      <div className="flex-1" />

      {tenantSettings?.organizationName && (
        <div className="flex min-w-0 items-center gap-2 overflow-hidden">
          <span
            className="flex size-6 shrink-0 items-center justify-center rounded-md bg-primary/15 text-[10px] font-bold uppercase text-primary"
            aria-hidden="true"
          >
            {initialsOf(tenantSettings.organizationName)}
          </span>
          <span
            className="min-w-0 truncate text-sm font-semibold tracking-tight"
            title={tenantSettings.organizationName}
          >
            {tenantSettings.organizationName}
          </span>
        </div>
      )}

      {/* Environment pill (Locus, 2026-09 topbar pass) - only renders
            once NEXT_PUBLIC_DEPLOY_ENV is baked into a build (wired
            2026-09-18: web/Dockerfile ARG, deploy-azure.yml build arg
            "production", deploy/docker-compose.yml build arg "staging" per
            RULE 0's .ai=prod/.xyz=staging split). Deliberately absent
            rather than a placeholder on any build that omits it, so this
            never claims an environment nobody configured. */}
      {/* "personal" is not an environment to warn about (it rendered in staging amber): no pill. */}
      {getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV") && getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV") !== "personal" && (
        <span
          className="hidden shrink-0 items-center gap-1.5 rounded-md px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide sm:inline-flex"
          style={{
            color:
              getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV") === "production" ? "var(--state-success)" : "var(--state-warning)",
            backgroundColor: `color-mix(in srgb, ${
              getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV") === "production" ? "var(--state-success)" : "var(--state-warning)"
            } 14%, transparent)`,
          }}
        >
          {/* "Live" pulse: a ping echo ring behind the solid dot, the same
                language as a status monitor's live indicator. Only pings on
                an actual production build - staging gets a calm solid dot,
                since staging is not something anyone should read as "live". */}
          <span className="relative flex size-1.5 shrink-0">
            {getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV") === "production" && (
              <span
                className="absolute inline-flex size-full animate-ping rounded-full opacity-75"
                style={{ backgroundColor: "currentColor" }}
              />
            )}
            <span className="relative inline-flex size-1.5 rounded-full" style={{ backgroundColor: "currentColor" }} />
          </span>
          {getPublicEnv("NEXT_PUBLIC_DEPLOY_ENV")}
        </span>
      )}

      {actions}

      {/* "How this page works" - the minimum to do on THIS page. Opens by
            itself once per page, never while the product tour is showing. */}
      <PageGuideButton isAdmin={isAdmin} />

      {/* Compact usage meter - "12/30 today" for platform-pool tenants,
            linking to /usage. Renders nothing for BYOK tenants or until the
            quota fetch resolves, so it never shifts the topbar for people
            it doesn't apply to. */}
      <UsageMeterPill quota={usageQuota} />

      <NotificationBell />

      {/* Instrument cluster (2026-09 topbar pass) - theme/edge/language/
            tour used to be four independent icons loose in the row; this
            is a pure visual grouping (one shared border/background frame)
            around the SAME four components, unmodified, in their existing
            order - not a new dropdown. Nesting them inside a fifth Popover
            was considered and deliberately skipped: none of their internal
            popover/dropdown positioning has been verified to behave inside
            another Popover's portal, and "regroup visually" doesn't require
            that risk to satisfy the design brief. */}
      <div
        className="flex items-center gap-0.5 rounded-md border border-[var(--surface-border)] p-0.5"
        style={{ backgroundColor: "var(--surface-card-alt)" }}
      >
        {/* Theme toggle — clean segmented control. NOTE: the icon-only
              buttons explicitly reset border/bg/padding/shadow because the
              global `button {}` base rule (globals.css) otherwise forces
              `px-3.5 py-2`, which on a size-7 (28px) button leaves 0px of
              content width and clips the icon away entirely. */}
        <div className="flex items-center gap-0.5" role="group" aria-label={tNav("themeGroupAriaLabel")}>
          <button
            type="button"
            onClick={() => setMode("dark")}
            aria-pressed={resolvedMode === "dark"}
            aria-label={tNav("darkThemeAriaLabel")}
            style={resolvedMode === "dark" ? { backgroundColor: "var(--surface-raised)" } : undefined}
            className={`flex size-7 items-center justify-center rounded border-0 p-0 shadow-none transition-colors ${resolvedMode === "dark" ? "text-[var(--text-primary)]" : "bg-transparent text-[var(--text-muted)] hover:bg-transparent hover:text-[var(--text-primary)]"}`}
          >
            <Moon className="size-4" aria-hidden="true" />
          </button>
          <button
            type="button"
            onClick={() => setMode("light")}
            aria-pressed={resolvedMode === "light"}
            aria-label={tNav("lightThemeAriaLabel")}
            style={resolvedMode === "light" ? { backgroundColor: "var(--surface-raised)" } : undefined}
            className={`flex size-7 items-center justify-center rounded border-0 p-0 shadow-none transition-colors ${resolvedMode === "light" ? "text-[var(--text-primary)]" : "bg-transparent text-[var(--text-muted)] hover:bg-transparent hover:text-[var(--text-primary)]"}`}
          >
            <Sun className="size-4" aria-hidden="true" />
          </button>
        </div>
        <EdgeSetupChip />
        <TwoFactorButton accountUrl={profileHref} />
        <LanguageSwitcher />
        <TourReopenButton />
      </div>
      {/* No language modal auto-opens any more (2026-10-10, direct
            instruction: do not show language detection). The locale is
            changed from the LanguageSwitcher globe above; with nothing
            calling setLanguagePromptPending, the first-run tour no longer
            waits on lib/firstRunGate. */}

      {/* System health — now the shared .state-pill / .state-dot
            primitives (design contract §3/§4) instead of hand-picked
            emerald-500 Tailwind palette values. A healthy core gets
            .state-dot-live, so "up" is signalled by MOTION as well as hue
            and is distinguishable from "offline" without relying on
            red-vs-green, which is the single most common CVD confusion. The
            expanding ring is already wrapped in prefers-reduced-motion by
            globals.css. */}
      <TrustStrip coreHealthy={coreHealthy} status={status} />
    </header>
  );
}
