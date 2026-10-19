// Loads WorkspaceSignals (prereq.ts) - the handful of read-only facts the goal
// cards need to be honest. Every probe is independent and nullable: one
// admin-only 403 must not blank out the rest, and "could not check" is a real
// answer that the cards report as such (see prereq.ts).
//
// The probes are injected (SignalsDeps) so this is unit-tested without the
// network, and so the page can hand it QUIET variants: several of these reads
// are admin-only, and the default API wrapper raises a global error toast on a
// 403 - an expected state for a non-admin, and the wrong thing to greet them
// with on a welcome page.

import type { WorkspaceSignals } from "./prereq";

export interface SignalsDeps {
  getTenantSettings: () => Promise<{ plan?: string | null } | null | undefined>;
  getProviderAvailability: () => Promise<{ canCreateAgents?: boolean } | null | undefined>;
  /** true = tutor on, false = definitively off; a rejection = could not check. */
  tutorAvailable: () => Promise<boolean>;
  /** Rejects with `.status` 404/503 when the deployment has no deep research. */
  getResearchStatus: () => Promise<{ enabled?: boolean; searchConfigured?: boolean } | null | undefined>;
  listMcpServers: () => Promise<ReadonlyArray<{ name?: string; enabled?: boolean }> | null | undefined>;
  /** null (a 204) = no credential; an object = one is active; a rejection = could not check. */
  getE2bCredential: () => Promise<unknown>;
}

function ok<T>(r: PromiseSettledResult<T>): T | undefined {
  return r.status === "fulfilled" ? r.value : undefined;
}

export async function loadWorkspaceSignals(deps: SignalsDeps): Promise<WorkspaceSignals> {
  const [settings, availability, tutor, research, mcp, e2b] = await Promise.allSettled([
    deps.getTenantSettings(),
    deps.getProviderAvailability(),
    deps.tutorAvailable(),
    deps.getResearchStatus(),
    deps.listMcpServers(),
    deps.getE2bCredential(),
  ]);

  const settingsValue = ok(settings);
  const plan = typeof settingsValue?.plan === "string" ? settingsValue.plan : null;

  const availabilityValue = ok(availability);
  const canCreateAgents =
    availability.status === "fulfilled" && typeof availabilityValue?.canCreateAgents === "boolean"
      ? availabilityValue.canCreateAgents
      : null;

  // deep research: a feature that is simply absent (404/503) is a definite
  // "no"; any other failure is "could not check".
  let deepResearch: boolean | null;
  if (research.status === "fulfilled") {
    deepResearch = Boolean(research.value?.enabled) && Boolean(research.value?.searchConfigured);
  } else {
    const status = (research.reason as { status?: number } | undefined)?.status;
    deepResearch = status === 404 || status === 503 ? false : null;
  }

  const mcpList = ok(mcp);
  const mcpEnabled = Array.isArray(mcpList)
    ? mcpList.filter((m) => m && m.enabled !== false && typeof m.name === "string").map((m) => m.name as string)
    : null;

  return {
    loaded: true,
    plan,
    // The plan read FAILED = fail closed, like the template gallery (a plan that
    // answered with anything but FREE counts as paid there, and here).
    planFailed: settings.status !== "fulfilled",
    canCreateAgents,
    tutorOn: tutor.status === "fulfilled" ? tutor.value : null,
    deepResearch,
    mcpEnabled,
    e2bReady: e2b.status === "fulfilled" ? Boolean(e2b.value) : null,
  };
}
