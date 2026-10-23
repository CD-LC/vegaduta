// The ONE capability map for "which console features does this install not have".
// Read by the gate in components/shell/FeatureGate.tsx (page body) and by anything
// that needs to explain a missing feature. The rail, this gate and the proxy's 404 all read the
// one EDITION_ROUTES table in lib/edition.ts; this file decides what a deep link to a missing
// feature SHOWS.
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
import {
  EDITION_ROUTES,
  isPersonalEdition,
  underRoute,
  type EditionIcon,
  type EditionRoute,
  type UnavailableReason,
} from "./edition";

// The route table itself (lib/edition.ts) is icon-free so the proxy can read it; this file adds the
// icon the "not available" state draws.
export { EDITION_ROUTES };
export type { EditionRoute, UnavailableReason };

const ICONS: Record<EditionIcon, LucideIcon> = {
  key: KeyRound,
  users: Users,
  card: CreditCard,
  network: Network,
  building: Building2,
  activity: Activity,
};

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

/** The deep-link gates: the rows of {@link EDITION_ROUTES} that render the "not available" state. */
export const EDITION_GATES: readonly EditionGate[] = EDITION_ROUTES.flatMap((r) =>
  r.gate && r.navKey !== null
    ? [{ route: r.route, navKey: r.navKey, reason: r.reason, icon: ICONS[r.icon], scope: r.scope }]
    : []
);

/** The gate that blocks {@code path} on this install, or null when the page is available. */
export function unavailableFeature(path: string): EditionGate | null {
  for (const gate of EDITION_GATES) {
    if (!underRoute(path, gate.route)) continue;
    if (gate.scope === "personal" && isPersonalEdition()) return gate;
    if (gate.scope === "no-code-intel" && !getPublicEnv("NEXT_PUBLIC_CODE_INTEL_URL")?.trim()) return gate;
  }
  return null;
}
