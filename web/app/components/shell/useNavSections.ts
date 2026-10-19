"use client";

// The resolved nav blueprint for the current user (role, permission, feature-flag and live-badge gating) plus the active-row logic. Lifted out of AppShell.tsx unchanged.

import { useAuth } from "../AuthProvider";
import { usePendingSenders } from "../../lib/usePendingSenders";
import { useAppChrome } from "../AppChromeProvider";
import { useDataTablesEnabled } from "../dataTables/useDataTablesEnabled";
import { useScheduledTasksEnabled } from "../scheduledTasks/useScheduledTasksEnabled";
import { useCodingTasksEnabled } from "../codingTasks/useCodingTasksEnabled";
import { useResearchEnabled } from "../research/useResearchEnabled";
import { filterBillingNav } from "../../lib/billing-flag";
import { hiddenOnPersonalInstall } from "../../lib/personalInstall";
import { getPublicEnv } from "../../lib/env";
import { NAV_SECTIONS, type NavItem, type NavSection } from "../nav/navSections";

/** Rows that point at a service this deployment does not run. */
function hiddenByDeployment(href: string): boolean {
  // A personal install (local auth) is one person on one machine: the
  // multi-tenant/platform surfaces in PERSONAL_HIDDEN_ROUTES stay off the rail
  // (lib/personalInstall.ts lists them and why).
  if (hiddenOnPersonalInstall(href)) return true;
  // Code Intel is its own service; with no URL configured there is nothing behind the page.
  if (href === "/code-intel" && !getPublicEnv("NEXT_PUBLIC_CODE_INTEL_URL")?.trim()) return true;
  return false;
}

export function useNavSections(pathname: string) {
  const { isAdmin, hasPermission, hasAdminScope } = useAuth();
  const { mcpAttentionCount } = useAppChrome();
  const pendingSenders = usePendingSenders(hasPermission("MANAGE_CHANNELS"));
  // Data Tables is flag-gated server-side (default OFF): its sidebar item and Ctrl+K entry appear only once the
  // config endpoint reports it on, so a flag-off deployment has no link to a dead page.
  const dataTablesEnabled = useDataTablesEnabled();
  // Scheduled tasks is flag-gated the same way (app.scheduled-tasks.enabled, default OFF).
  const scheduledTasksEnabled = useScheduledTasksEnabled();
  // Coding tasks too (app.coding-harness.enabled, default OFF).
  const codingTasksEnabled = useCodingTasksEnabled();
  // Research runs too (app.research.runs.enabled, default OFF): the page only says "not available" otherwise.
  const researchEnabled = useResearchEnabled();
  // Resolve the static section blueprint against the current user: drop
  // admin-only sections (Administration) for non-admins, and drop
  // admin-hidden items (HOME - "/" redirects straight to /admin for admins,
  // see app/page.tsx's `if (isAdmin) router.replace("/admin")`, so it'd be a
  // redundant duplicate of the ADMIN item). Empty sections are pruned so no
  // orphan header ever renders.
  // filterBillingNav: plans/billing rows drop out when billing is off
  // (lib/billing-flag.ts); with it on this is the unfiltered blueprint.
  const navSections = filterBillingNav(NAV_SECTIONS)
    .map((section) => ({
      ...section,
      items: section.items
        // adminOnly = tenant-admin ROLE required; perm = one granular scope
        // required (the PERM_* the backend enforces). A row needs every gate
        // it declares satisfied.
        .filter(
          (item) =>
            !(item.adminHidden && isAdmin) &&
            !(item.adminOnly && !isAdmin) &&
            !(item.perm && !hasPermission(item.perm)) &&
            !(item.href === "/data-tables" && !dataTablesEnabled) &&
            !(item.href === "/scheduled" && !scheduledTasksEnabled) &&
            !(item.href === "/coding" && !codingTasksEnabled) &&
            !(item.href === "/research" && !researchEnabled) &&
            !hiddenByDeployment(item.href)
        )
        // /build used to be dropped here when this build had no app-builder URL
        // baked in. It no longer is: the page's default "Guided build" tab talks
        // only to core and works on every deployment, and its "App Builder" tab
        // renders its own honest not-available state when the service is absent.
        // TOOLS is the only item with a live computed badge today - every
        // other item keeps its static `badge: null`. Kept as an override here
        // rather than a field on NAV_SECTIONS itself so that static blueprint
        // stays free of component state.
        .map((item): NavItem =>
          item.href === "/tools" && mcpAttentionCount > 0
            ? { ...item, badge: mcpAttentionCount, badgeVariant: "attention" as const }
            : item.href === "/admin/contacts" && pendingSenders > 0
              ? { ...item, badge: pendingSenders, badgeVariant: "attention" as const }
              : item
        ),
      // A section renders when it has any visible item left AND its own gate
      // passes - adminOnly sections now key off hasAdminScope (tenant-admin OR
      // any granular admin scope) so a delegated user sees the ADMINISTRATION
      // group containing only the screens their permissions cover.
    }))
    .filter((section) => (!section.adminOnly || hasAdminScope) && section.items.length > 0);

  // Flat view of every currently-visible destination - the active-state logic
  // below needs to compare an item against all its siblings regardless of
  // which section they live in.
  const railItems = navSections.flatMap((section) => section.items);

  // Direct feedback: "give a proper navigation highlight for admin/
  // maintenance like other options in menu" - the naive `pathname.
  // startsWith(item.href)` check below made ADMIN ("/admin") AND MAINT
  // ("/admin/maintenance") both light up on the Maintenance page, since
  // "/admin/maintenance" starts with "/admin" too. Fixed by only ever
  // highlighting the single most-specific (longest-href) match among all
  // rail items - MAINT wins over ADMIN on /admin/maintenance, ADMIN alone
  // lights up on /admin itself. The "+ '/'" guard also stops a hypothetical
  // "/admin2" route from falsely matching "/admin".
  function isActive(item: NavItem) {
    if (item.exact) return pathname === item.href;
    if (pathname !== item.href && !pathname.startsWith(item.href + "/")) return false;
    const moreSpecificMatchExists = railItems.some(
      (other) =>
        other.href !== item.href &&
        other.href.length > item.href.length &&
        (pathname === other.href || pathname.startsWith(other.href + "/"))
    );
    return !moreSpecificMatchExists;
  }
  const sectionActive = (section: NavSection) => section.items.some((item) => isActive(item));
  // Aggregate badge bubble on a hub glyph (e.g. Connect's icon carries the
  // pending-integrations count that TOOLS shows inside its flyout).
  const sectionBadgeCount = (section: NavSection) =>
    section.items.reduce((n, i) => n + (typeof i.badge === "number" ? i.badge : i.badge ? 1 : 0), 0);
  return { navSections, isActive, sectionActive, sectionBadgeCount };
}
