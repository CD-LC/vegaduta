// The ONE capability map for "which console features does this install not have".
// Read by the gate in components/shell/FeatureGate.tsx (page body) and by anything
// that needs to explain a missing feature. lib/personalInstall.ts still decides what
// the rail ADVERTISES; this file decides what a deep link to a missing feature SHOWS.
//
// Each entry names the missing dependency, verified against the personal stack
// (deploy/compose/personal.yml + application-personal.yml: Postgres, Valkey, core, web;
// no Keycloak, no billing, one local owner):
//   identity - sign-in is core's local account, there is no Keycloak to configure SSO on
//   team     - one person on one machine: nothing to share, delegate or issue keys for
//   billing  - app.edition.billing-enabled is false: no plans, wallets or gateways
//   hosted   - a multi-tenant / public-facing surface that a single local box does not run
//   service  - a separate service (Code Intel) this deployment has no URL for
import { Activity, Building2, CreditCard, KeyRound, Network, Users, type LucideIcon } from "lucide-react";
import { getPublicEnv } from "./env";
import { isPersonalInstall } from "./personalInstall";

export type UnavailableReason = "identity" | "team" | "billing" | "hosted" | "service";

export interface EditionGate {
  /** Route prefix (the route and everything nested under it). */
  route: string;
  /** `nav` i18n key naming the feature. */
  navKey: string;
  reason: UnavailableReason;
  icon: LucideIcon;
  /** Gate applies only on a personal install (default), or whenever the service is not configured. */
  scope: "personal" | "no-code-intel";
}

const personal = (route: string, navKey: string, reason: UnavailableReason, icon: LucideIcon): EditionGate => ({
  route,
  navKey,
  reason,
  icon,
  scope: "personal",
});

export const EDITION_GATES: readonly EditionGate[] = [
  personal("/admin/sso", "railSso", "identity", KeyRound),
  personal("/shared-with-me", "railShared", "team", Users),
  personal("/admin/agent-trust", "railAgentTrust", "team", Users),
  personal("/admin/api-keys", "railApiKeys", "team", KeyRound),
  personal("/admin/billing", "railBill", "billing", CreditCard),
  personal("/features", "railPlans", "billing", CreditCard),
  personal("/roi", "railRoi", "billing", CreditCard),
  personal("/admin/payments", "railPayments", "billing", CreditCard),
  personal("/admin/commerce", "railCommerce", "billing", CreditCard),
  personal("/directory", "railDirectory", "hosted", Network),
  personal("/studio", "railStudio", "hosted", Building2),
  personal("/admin/leads", "railLeads", "hosted", Building2),
  personal("/admin/branding", "railBrand", "hosted", Building2),
  personal("/admin/mcp-servers", "railMcp", "hosted", Network),
  { route: "/code-intel", navKey: "railCode", reason: "service", icon: Activity, scope: "no-code-intel" },
];

const under = (path: string, route: string): boolean => path === route || path.startsWith(`${route}/`);

/** The gate that blocks {@code path} on this install, or null when the page is available. */
export function unavailableFeature(path: string): EditionGate | null {
  for (const gate of EDITION_GATES) {
    if (!under(path, gate.route)) continue;
    if (gate.scope === "personal" && isPersonalInstall()) return gate;
    if (gate.scope === "no-code-intel" && !getPublicEnv("NEXT_PUBLIC_CODE_INTEL_URL")?.trim()) return gate;
  }
  return null;
}
