"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { usePathname } from "next/navigation";
import { motion } from "motion/react";
import { useAuth } from "./AuthProvider";
import { useTranslations } from "../lib/i18n";
import BrandIdentityModal from "./BrandIdentityModal";
import PanelAccentAssigner from "./PanelAccentAssigner";
import { useSystemHealth } from "./SystemHealthProvider";
import { maintenanceActive, refreshPlatformStatus, usePlatformStatus } from "../lib/platformStatus";
import MaintenanceScreen from "./MaintenanceScreen";
import AvatarUploadModal from "./AvatarUploadModal";
import { useShellLoadingCover, RouteTransitionOverlay } from "./RouteTransition";
import { UsageAlertBanner } from "./UsageQuota";
import { useDocumentTitle } from "../lib/useDocumentTitle";
import DesktopScreenDock from "./desktop/DesktopScreenDock";
import { useAppChrome } from "./AppChromeProvider";
// Rail "Jump to..." box + global Cmd/Ctrl+K both open this - a hand-rolled
// modal. Fed the SAME already-filtered `navSections` this file renders, not a
// re-derived copy, so it never drifts from admin-gating/live-badge logic.
import CommandPalette from "./CommandPalette";
import { listenGlobalPaletteShortcut } from "../lib/paletteShortcut";
import { LEGAL_ENTITY } from "../lib/legalEntity";
import type { NavItem, NavSection } from "./nav/navSections";
import { useNavSections } from "./shell/useNavSections";
import { useShellBrand } from "./shell/useShellBrand";
import { useShellMedia } from "./shell/useShellMedia";
import { useMobileDrawer } from "./shell/useMobileDrawer";
import { useTopbarCompact } from "./shell/useTopbarCompact";
import { useHubFlyout } from "./shell/useHubFlyout";
import { railClasses } from "./shell/railClasses";
import RailBrand from "./shell/RailBrand";
import RailJump from "./shell/RailJump";
import HubRail from "./shell/HubRail";
import ClassicRail from "./shell/ClassicRail";
import RailFooter from "./shell/RailFooter";
import HubFlyout from "./shell/HubFlyout";
import TopBar from "./shell/TopBar";
import FeatureGate from "./shell/FeatureGate";
// Breadcrumb + links between the control pages (runtime settings, instructions, approvals, guardrails, turn inspector); renders nothing elsewhere.
import ControlsBreadcrumb from "./controls/ControlsBreadcrumb";

export type { NavItem, NavSection };

// The shell is split under ./shell: the rail pieces (RailBrand, RailJump,
// HubRail, ClassicRail, NavRow, RailFooter, HubFlyout), the TopBar and the
// hooks (nav gating, drawer + product-tour auto-open, hub flyout, topbar
// compacting). The product-tour anchors for the rail rows are in
// ./shell/navTourTargets.ts; the rail's scroll container below keeps its own
// data-tour marker so the tour test can find it here.

/**
 * Persistent app shell - a fixed 74px left icon rail (AXON design-handoff
 * spec) for primary navigation, plus a 60px topbar carrying brand/org/page
 * title/search/theme/language/status/account.
 *
 * `title`/`eyebrow` render in the topbar so every page keeps a clear,
 * single H1 (still exactly "Agentic AI Platform" on the dashboard and
 * "Admin Control Panel" on the admin overview, matching the existing
 * Playwright suite's heading assertions) instead of each page re-declaring
 * its own header chrome. `actions` is an optional slot for page-specific
 * buttons (e.g. "+ New Workflow") that belong next to the title, not
 * buried in page content. `wide`/`bleed` relax .app-content's max-width
 * for screens (the workflow canvas) that need the full viewport instead of
 * the console's usual centered reading column.
 */
interface AppShellBodyProps {
  title?: ReactNode;
  eyebrow?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
  wide?: boolean;
  bleed?: boolean;
  collapsibleRail?: boolean;
  /**
   * Editor pages (the workflow designer) that must fit the viewport exactly:
   * the frame becomes 100dvh with no page scroll, the main region clips
   * instead of growing, and the copyright footer is not rendered. Implies
   * `bleed`. The page owns all scrolling inside its own panels.
   */
  fillViewport?: boolean;
  /**
   * Conversation pages (the Assistant): the top bar's status chips (system health, browser models) show as
   * quiet icons with their words in a tooltip and for screen readers, so they do not compete with the chat.
   */
  quietStatus?: boolean;
}

function AppShellBody({
  title = null,
  eyebrow,
  actions = null,
  children,
  wide = false,
  bleed: bleedProp = false,
  fillViewport = false,
  // Default true (2026-09-18): the whole rail collapses to icons everywhere,
  // not just on the workflow editor.
  collapsibleRail = true,
  quietStatus = false,
}: AppShellBodyProps) {
  const bleed = bleedProp || fillViewport;
  const pathname = usePathname();
  useDocumentTitle(title, eyebrow);
  const { isPlatformAdmin, hasPermission, accountUrl } = useAuth();
  // Resolved once per render - null until Keycloak's init() has populated the
  // realm endpoints, in which case we fall back to a non-interactive label
  // rather than a dead link (see auth.accountUrl).
  const profileHref = accountUrl?.() ?? null;
  const tNav = useTranslations("nav");

  // Session-lived shell chrome (tenantSettings/account/avatar/MCP attention
  // badge/usage quota) - fetched ONCE per session by AppChromeProvider at the
  // root layout (see AppChromeProvider.tsx for the perf-audit context).
  const { setAccount, usageQuota } = useAppChrome();
  const { navSections, isActive, sectionActive, sectionBadgeCount } = useNavSections(pathname);
  // White-label entitlement + the tenant's own brand mark for the rail.
  const { isWhiteLabel, brandLogoUrl, brandWordmark, orgInitials, tenantBrandEmpty, showTenantBrand } = useShellBrand();

  // avatarModalOpen / brandOpen are this component's own transient UI state.
  const [avatarModalOpen, setAvatarModalOpen] = useState(false);
  // The left logo opens the brand identity showcase (HOME has its own rail item).
  const [brandOpen, setBrandOpen] = useState(false);

  // Rail section fold state: empty set = every section open by default;
  // nothing is ever hidden behind this, only foldable on request.
  const [closedGroups, setClosedGroups] = useState<Set<string>>(new Set());
  const toggleGroup = useCallback((labelKey: string) => {
    setClosedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(labelKey)) next.delete(labelKey);
      else next.add(labelKey);
      return next;
    });
  }, []);

  // Global Cmd/Ctrl+K - opens the same CommandPalette the rail's "Jump to..."
  // row opens. Registered here (not inside CommandPalette) so it works even
  // while the palette is unmounted (it early-returns null when closed).
  const [paletteOpen, setPaletteOpen] = useState(false);
  // A surface with its own Cmd/Ctrl+K palette (the workflow canvas) claims the
  // chord first - see lib/paletteShortcut.ts - so only one palette ever opens.
  useEffect(() => listenGlobalPaletteShortcut(() => setPaletteOpen(true)), []);
  // The badge/tooltip shows the platform's modifier glyph. Detected client-side
  // only, after mount, so SSR/first paint stays platform-neutral.
  const [shortcutLabel, setShortcutLabel] = useState("Ctrl+K");
  useEffect(() => {
    if (/Mac|iPod|iPhone|iPad/.test(navigator.platform || navigator.userAgent || "")) {
      setShortcutLabel("⌘K");
    }
  }, []);

  // Desktop breakpoint + hover-capable pointer (see shell/useShellMedia.ts).
  const { isDesktop, isHoverCapable } = useShellMedia();
  // Off-canvas drawer below lg, its focus handling, and the product tour's
  // auto-open of it (see shell/useMobileDrawer.ts for the state machine).
  const { mobileNavOpen, setMobileNavOpen, navRef, mobileMenuButtonRef, markTourNavigation, tourActive } =
    useMobileDrawer(pathname, isDesktop);
  // Overflow-driven topbar compacting (data-compact 0/1/2).
  const topbarRef = useTopbarCompact();

  // Hub mode: a rail of section glyphs with a hover flyout, on desktop with a
  // hovering pointer and no active tour (every data-tour anchor lives in the
  // classic rows, which must stay mounted while a tour runs).
  const hubMode = collapsibleRail && isDesktop && isHoverCapable && !tourActive;
  const {
    flyout,
    setFlyout,
    flyoutPanelRef,
    cancelFlyoutClose,
    scheduleFlyoutClose,
    openFlyout,
    openFlyoutAndFocusFirst,
  } = useHubFlyout(pathname, hubMode);
  const flyoutSection = flyout ? navSections.find((s) => s.labelKey === flyout.key) : undefined;
  const { railLabelCls, railRowCollapse } = railClasses(collapsibleRail, hubMode);

  // Shared, polled system-health signal (SystemHealthProvider is mounted at the
  // root layout). `coreReachable` is optimistic and only flips false after
  // repeated failed probes; the topbar status pill is healthy unless core is
  // reachable-but-CRITICAL (a hard-down core is covered by the maintenance
  // takeover).
  const { coreReachable, status, refresh } = useSystemHealth();
  // Planned maintenance (web-admin /maintenance): core 503s every
  // non-platform-admin call, so the shell is replaced by the maintenance
  // screen. Platform admins keep access server-side, so they keep the shell.
  const platformStatus = usePlatformStatus();
  const inMaintenance = maintenanceActive(platformStatus) && !isPlatformAdmin;
  const coreHealthy = coreReachable && status?.overallStatus !== "CRITICAL";

  // useShellLoadingCover() registers this shell's presence (suppressing the
  // root-level viewport badge - see RouteTransition.tsx's module comment) and
  // returns whether ITS OWN badge, pinned inside <main> only below, should
  // currently be shown. Disabled while core is unreachable: the branch below
  // replaces the whole shell with MaintenanceScreen.
  const contentCoverActive = useShellLoadingCover(coreReachable && !inMaintenance);

  const closeDrawer = () => setMobileNavOpen(false);
  const onNavigate = () => {
    markTourNavigation();
    setMobileNavOpen(false);
  };

  // Core is truly down (optimistic threshold crossed) - take the whole shell
  // over with the "We'll be back" screen. The provider keeps polling
  // underneath, so this clears itself when core recovers; Retry forces a probe.
  if (inMaintenance) {
    return (
      <MaintenanceScreen
        onRetry={() => void refreshPlatformStatus(fetch, { force: true })}
        maintenance={{
          message: platformStatus.status?.maintenance.message ?? null,
          endsAt: platformStatus.status?.maintenance.endsAt ?? null,
        }}
      />
    );
  }
  if (!coreReachable) {
    return <MaintenanceScreen onRetry={refresh} />;
  }

  return (
    <div className={`flex bg-background text-foreground ${fillViewport ? "h-dvh overflow-hidden" : "min-h-screen"}`}>
      {/* Mobile drawer backdrop. Tinted from --surface-sunken (the darkest
          rung) rather than a raw black, so it stays on-theme in light mode
          too instead of slamming the page with pure black. */}
      {mobileNavOpen && (
        <div
          className="fixed inset-0 z-40 backdrop-blur-sm lg:hidden"
          style={{ background: "color-mix(in srgb, var(--surface-sunken) 72%, transparent)" }}
          onClick={closeDrawer}
          aria-hidden="true"
        />
      )}

      <CommandPalette open={paletteOpen} onOpenChange={setPaletteOpen} sections={navSections} />

      {/* --------------------------------------------------------- Sidebar
          Next-gen labeled sidebar: fixed off-canvas drawer on mobile, an
          in-flow sticky column on lg+.

          DEPTH (design contract §1): the rail is painted at --surface-card
          (L*=9.95) while the page canvas behind it sits at --surface-page
          (L*=4.42); lightness is the only depth cue on dark surfaces, and a
          full rung of it is what makes this column read as chrome. The same
          value is used for the topbar, so the whole shell sits on one plane
          and the canvas sits under it. */}
      <nav
        ref={navRef}
        aria-label={tNav("primaryNavAriaLabel")}
        style={{ backgroundColor: "var(--surface-card)" }}
        className={`group/rail fixed inset-y-0 left-0 z-50 flex w-56 flex-col border-r border-[var(--surface-border)] ease-out lg:top-0 lg:h-screen lg:translate-x-0 ${
          collapsibleRail
            ? hubMode
              ? // Hub mode: the rail is a permanent 64px icon lane - it no
                // longer expands on hover (the flyout carries the labels).
                "transition-[width,transform] duration-200 lg:fixed lg:w-16 lg:overflow-x-hidden"
              : "transition-[width,transform] duration-200 lg:fixed lg:w-16 lg:overflow-x-hidden lg:hover:w-56 lg:hover:shadow-2xl"
            : "transition-transform duration-300 lg:sticky lg:w-56"
        } ${mobileNavOpen ? "translate-x-0" : "-translate-x-full"}`}
        // Below lg, a closed drawer is only translated off-screen - its links
        // and buttons stayed fully focusable while invisible (a11y audit
        // follow-up). `inert` drops the whole subtree out of both the tab
        // order and the accessibility tree while it's off-canvas; skipped at
        // desktop widths, where the nav is always in flow via `lg:`, never a
        // drawer, regardless of `mobileNavOpen`.
        inert={!isDesktop && !mobileNavOpen}
      >
        <RailBrand
          collapsibleRail={collapsibleRail}
          hubMode={hubMode}
          railLabelCls={railLabelCls}
          showTenantBrand={showTenantBrand}
          brandLogoUrl={brandLogoUrl}
          brandWordmark={brandWordmark}
          orgInitials={orgInitials}
          onOpenBrand={() => setBrandOpen(true)}
        />

        {/* Destinations - grouped into labelled sections. Each section sets
            `--cat` once and every icon in it (plus the header text and the
            active row's accent rail) inherits it; colour is never the only
            cue: every row carries its own icon and every group a written
            header. PRODUCT-TOUR ANCHOR: the rail itself (the platform-admin
            walk spotlights it). */}
        <div
          className="flex-1 overflow-y-auto px-2.5 pb-3 pt-2 [scrollbar-width:thin] [scrollbar-color:transparent_transparent] hover:[scrollbar-color:var(--surface-border-strong)_transparent] [&::-webkit-scrollbar]:w-[5px] [&::-webkit-scrollbar-track]:bg-transparent [&::-webkit-scrollbar-thumb]:bg-transparent hover:[&::-webkit-scrollbar-thumb]:bg-[var(--surface-border-strong)]"
          data-tour="nav-rail"
        >
          {/* "Jump to..." - the density escape valve for 33 destinations.
              Opens the same CommandPalette Cmd/Ctrl+K opens. */}
          <RailJump
            hubMode={hubMode}
            shortcutLabel={shortcutLabel}
            railRowCollapse={railRowCollapse}
            railLabelCls={railLabelCls}
            onOpen={() => setPaletteOpen(true)}
          />
          {hubMode ? (
            <HubRail
              navSections={navSections}
              isActive={isActive}
              sectionActive={sectionActive}
              sectionBadgeCount={sectionBadgeCount}
              flyoutKey={flyout?.key}
              openFlyout={openFlyout}
              openFlyoutAndFocusFirst={openFlyoutAndFocusFirst}
              scheduleFlyoutClose={scheduleFlyoutClose}
              closeFlyout={() => setFlyout(null)}
              markTourNavigation={markTourNavigation}
            />
          ) : (
            <ClassicRail
              navSections={navSections}
              closedGroups={closedGroups}
              toggleGroup={toggleGroup}
              isActive={isActive}
              railRowCollapse={railRowCollapse}
              railLabelCls={railLabelCls}
              onNavigate={onNavigate}
            />
          )}
        </div>

        <RailFooter
          profileHref={profileHref}
          hubMode={hubMode}
          collapsibleRail={collapsibleRail}
          onOpenAvatar={() => setAvatarModalOpen(true)}
        />
      </nav>

      {/* -------------------------------------------------- Section flyout
          HUB RAIL companion: it MUST be a sibling of <nav>, not a child - the
          nav is overflow-x-hidden and transformed, so even position:fixed
          children are clipped to the 64px lane. */}
      {hubMode && flyoutSection && flyout && (
        <HubFlyout
          section={flyoutSection}
          top={flyout.top}
          panelRef={flyoutPanelRef}
          cancelClose={cancelFlyoutClose}
          scheduleClose={scheduleFlyoutClose}
          isActive={isActive}
          markTourNavigation={markTourNavigation}
        />
      )}

      <AvatarUploadModal open={avatarModalOpen} onClose={() => setAvatarModalOpen(false)} onUploaded={setAccount} />

      {/* Collapsed-rail spacer: the rail is lg:fixed when collapsible, so this
          reserves its 64px lane in flow - the rail then expands OVER the canvas
          on hover instead of reflowing it. */}
      {collapsibleRail && <div className="hidden shrink-0 lg:block lg:w-16" aria-hidden="true" />}

      {/* ----------------------------------------------------------- Main */}
      <div className={`relative flex min-w-0 flex-1 flex-col${fillViewport ? " min-h-0" : ""}`}>
        {/* Ambient aurora (2026-08-12 depth pass): .app-aurora in globals.css.
            Transform-only animation, pointer-events:none, killed by
            prefers-reduced-motion. Sits under the sticky header (z-30) and
            <main> (relative) because both are positioned later siblings. */}
        <div className="app-aurora" aria-hidden="true" />
        <TopBar
          headerRef={topbarRef}
          mobileMenuButtonRef={mobileMenuButtonRef}
          mobileNavOpen={mobileNavOpen}
          onToggleMobileNav={() => setMobileNavOpen((open) => !open)}
          title={title}
          eyebrow={eyebrow}
          actions={actions}
          profileHref={profileHref}
          coreHealthy={coreHealthy}
          status={status}
          quietStatus={quietStatus}
        />

        <motion.main
          key={pathname}
          className={
            fillViewport
              ? "relative flex min-h-0 flex-1 flex-col overflow-hidden"
              : bleed
                ? "relative flex flex-1 flex-col"
                : `relative flex-1 px-4 py-6 sm:px-6 lg:px-8 ${wide ? "" : ""}`
          }
          initial={{ opacity: 0, y: 10 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
        >
          {bleed ? (
            <FeatureGate>{children}</FeatureGate>
          ) : (
            <div className={`mx-auto w-full ${wide ? "max-w-none" : "max-w-7xl"}`}>
              {/* >=80%/100% usage warning with the upgrade CTA - dismissible,
                  follows the user to every console page rather than only
                  warning people who happen to open /usage. Deliberately not
                  rendered on `bleed` (full-viewport canvas) pages, whose
                  layout owns the whole viewport. */}
              <UsageAlertBanner quota={usageQuota} canManageBilling={hasPermission("MANAGE_BILLING")} />
              <ControlsBreadcrumb />
              <FeatureGate>{children}</FeatureGate>
            </div>
          )}
          {/* Content-scoped loading badge (see the contentCoverActive comment
              above), pinned inside this main region so it never sits on the
              rail/topbar. `absolute` relies on this <main> being `relative`. */}
          <RouteTransitionOverlay active={contentCoverActive} variant="content" />
        </motion.main>

        {/* Global console footer - sits at the bottom of the main content
            column, never overlapping the sidebar. Not on fillViewport editors:
            49px under a full-height canvas was one of the rows that made the
            workflow designer page scroll. */}
        {!fillViewport && (
          <footer className="mt-auto border-t border-border px-4 py-4 text-xs text-muted-foreground sm:px-6 lg:px-8">
            © {new Date().getFullYear()} {LEGAL_ENTITY}. All rights reserved.
          </footer>
        )}
      </div>

      <PanelAccentAssigner />
      {/* Persistent live-screen dock: renders nothing until the desktop feature answers,
          and polls the screen only while the person keeps it open. */}
      <DesktopScreenDock />
      {/* Never mounted-open for a white-labeled tenant - the rail brand has no
          way to open it (isWhiteLabel branch has no onClick), but skip
          rendering the modal entirely too so there's no path back to our own
          brand showcase for these tenants. */}
      {(!isWhiteLabel || tenantBrandEmpty) && (
        <BrandIdentityModal open={brandOpen} onClose={() => setBrandOpen(false)} />
      )}
    </div>
  );
}

// The shell consumes the shared system-health context via useSystemHealth().
// The SystemHealthProvider is mounted once at the ROOT layout (app/layout.tsx),
// NOT here - every page wraps its own content in <AppShell>, so the page
// component itself sits ABOVE AppShell in the tree; mounting the provider here
// would leave those page-level useSystemHealth() calls (e.g. the Knowledge
// page's sidecar gate) reading the default context. A root-level provider makes
// every page a descendant, and keeps a single poll across navigations.
export default function AppShell(props: AppShellBodyProps) {
  return <AppShellBody {...props} />;
}
