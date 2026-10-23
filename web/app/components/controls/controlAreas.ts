import { CheckCircle2, Bot, ScrollText, ShieldAlert, SlidersHorizontal, Waypoints, type LucideIcon } from "lucide-react";

/**
 * The places in the console where agent behaviour is controlled. One list feeds the breadcrumb and "related
 * controls" strip on those pages and the "Where do I control...?" help page, so the five admin pages and the agent
 * tab are always reachable from each other under the same names. The names themselves live in messages
 * "controls" (areas.<id>.title); the pages' own headings use the same words.
 */
export type ControlAreaId = "runtimeSettings" | "prompts" | "approvals" | "guardrails" | "turns" | "agentBehavior";

export interface ControlArea {
  id: ControlAreaId;
  href: string;
  icon: LucideIcon;
  /** Scope needed to use the page; undefined = every member. Tenant admins always pass. */
  perm?: string;
  /** Whether the page shows the breadcrumb (the agent tab lives inside the agent workbench, which has its own header). */
  breadcrumb: boolean;
}

export const CONTROL_AREAS: readonly ControlArea[] = [
  { id: "runtimeSettings", href: "/admin/runtime-settings", icon: SlidersHorizontal, perm: "MANAGE_TENANT_SETTINGS", breadcrumb: true },
  { id: "prompts", href: "/admin/instructions", icon: ScrollText, perm: "MANAGE_TENANT_SETTINGS", breadcrumb: true },
  { id: "approvals", href: "/admin/approvals", icon: CheckCircle2, perm: "MANAGE_APPROVALS", breadcrumb: true },
  { id: "guardrails", href: "/admin/guardrails", icon: ShieldAlert, perm: "MANAGE_GUARDRAILS", breadcrumb: true },
  { id: "turns", href: "/agents/turns", icon: Waypoints, perm: "VIEW_AUDIT_LOG", breadcrumb: true },
  { id: "agentBehavior", href: "/agents?section=behavior", icon: Bot, breadcrumb: false },
];

export const CONTROLS_HELP_HREF = "/help/controls";

const pathOf = (href: string) => href.split("?")[0];

/** The breadcrumb area for a pathname ("/admin/runtime-settings/anything" counts), or null on every other page. */
export function controlAreaForPath(pathname: string | null | undefined): ControlArea | null {
  if (!pathname) return null;
  const path = pathname.replace(/\/+$/, "") || "/";
  return CONTROL_AREAS.find((a) => a.breadcrumb && (path === pathOf(a.href) || path.startsWith(`${pathOf(a.href)}/`))) ?? null;
}

/** Areas this person can open: the page's scope, or tenant admin. A link that would only 403 is not offered. */
export function reachableAreas(can: { isAdmin: boolean; hasPermission: (name: string) => boolean }): ControlArea[] {
  return CONTROL_AREAS.filter((a) => !a.perm || can.isAdmin || can.hasPermission(a.perm));
}
