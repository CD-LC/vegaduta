"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { AGENT_LIMIT } from "../../../lib/planLimits";
import { resolveProviderGate } from "../../../lib/providerGate";
import type { WorkbenchProps } from "./types";
import type { useWorkbenchBase } from "./useWorkbenchBase";

export function useWorkbenchTenant(p: WorkbenchProps, prev: ReturnType<typeof useWorkbenchBase>) {
  const { providers, agents } = p;
  const { activeProviders } = prev;

  // AgentResponse.experienceMode's own javadoc: "Null means inherit the
  // tenant's current default... The web console resolves this against GET
  // /api/tenant/settings' experienceMode." That resolution was never
  // actually built here - effectiveMode() below used to hardcode "ADVANCED"
  // as the fallback for a null per-agent override, ignoring this tenant
  // default entirely. For a tenant whose default is SIMPLE, any agent still
  // inheriting it (experienceMode === null) was silently treated as
  // ADVANCED by this screen - showing the manual TOOLS · MCP picker (and
  // firing the /available-tools fetch) when the toggle's own visual state
  // still read "auto-select", and skewing toggleMode()'s SIMPLE/ADVANCED
  // flip logic off the wrong current value.
  const [tenantDefaultMode, setTenantDefaultMode] = useState("ADVANCED");

  // Direct feedback: "disable model selection for free users". Same
  // tenantPlan/isFreePlan shape ProvidersPanel.tsx already uses to hide its
  // "Use platform LLM" toggle and BYOK provider cards for FREE tenants -
  // this screen's own "Change model" button is gated on it too
  // (upgradeLocked={isFreePlan} lower down), since a FREE tenant picking a
  // BYOK provider/model here would be pointless: AgentChatService silently
  // ignores it and always routes FREE chat through the platform-hosted pool.
  const [tenantPlan, setTenantPlan] = useState(null);

  // Live per-plan agent cap from TenantSettingsResponse#maxAgents (null =
  // no cap, e.g. UNLIMITED) - see atAgentLimit below, which falls back to
  // the FREE-tier AGENT_LIMIT constant only until this has loaded.
  const [maxAgents, setMaxAgents] = useState(null);

  // A paid tenant can opt into the managed platform-hosted pool from
  // ProvidersPanel.tsx ("Use platform LLM"). When they have, a per-agent
  // provider/model pick is silently ignored just like for FREE - so this
  // screen treats "on the managed pool" as (FREE plan) OR (usePlatformLlm),
  // not FREE alone (see onPlatformLlm below).
  const [tenantUsePlatformLlm, setTenantUsePlatformLlm] = useState(false);

  // WB-006: a fetch failure used to leave tenantPlan permanently null with no
  // retry, so isFreePlan (below) read permanently false - FAILING OPEN and
  // showing an actual FREE tenant every paid-tier control unlocked. This flag
  // tracks "we've confirmed the load failed" (after one retry) so isFreePlan
  // can fail CLOSED instead once that's known, while staying permissive
  // during the brief normal window before the first response lands.
  const [tenantSettingsError, setTenantSettingsError] = useState(false);

  const applyTenantSettings = useCallback(
    (s: { experienceMode?: string; plan?: string; usePlatformLlm?: boolean; maxAgents?: number | null }) => {
      setTenantDefaultMode(s.experienceMode || "ADVANCED");
      setTenantPlan(s.plan || null);
      setTenantUsePlatformLlm(!!s.usePlatformLlm);
      setMaxAgents(typeof s.maxAgents === "number" ? s.maxAgents : null);
      setTenantSettingsError(false);
    },
    []
  );

  const fetchTenantSettings = useCallback(() => {
    setTenantSettingsError(false);
    return api
      .getTenantSettings()
      .then(applyTenantSettings)
      .catch(() =>
        // One automatic retry after a short delay before surfacing an error -
        // covers a transient blip without flickering the UI on the common
        // case where the first attempt just hasn't resolved yet.
        new Promise((resolve) => setTimeout(resolve, 2500)).then(() =>
          api
            .getTenantSettings()
            .then(applyTenantSettings)
            .catch(() => setTenantSettingsError(true))
        )
      );
  }, [applyTenantSettings]);

  useEffect(() => {
    void fetchTenantSettings();
  }, [fetchTenantSettings]);

  const [tenantSettingsNoticeDismissed, setTenantSettingsNoticeDismissed] = useState(false);

  // Fail CLOSED, not open: while unresolved (tenantPlan still null, no
  // confirmed error yet) this stays permissive to avoid flicker during the
  // normal brief initial load. Once tenantSettingsError is true - the fetch
  // and its retry both failed - every isFreePlan-gated control on this
  // screen (Change model, Templates, Delete, Fallback, Cost routing,
  // Voice/Video, validate()'s provider check) must render in its locked/
  // restrictive state, since we can no longer tell a FREE tenant apart from
  // a paid one and unlocked-by-default is the unsafe direction.
  const isFreePlan = tenantSettingsError || tenantPlan === "FREE";

  // The server's own answer to "does this tenant run on the hosted pool?"
  // (2026-09-19). This screen used to derive that itself as FREE || the
  // usePlatformLlm toggle - but core's rule (TenantLimits#usesPlatformPool)
  // has a third arm: a LIVE COMPLIMENTARY GRANT. Every new signup holds one
  // (all features unlocked until 2027-01-01), so a brand-new consumer is not
  // FREE, never flipped the toggle, has no BYOK key - and is served by the
  // hosted pool perfectly well. The two-arm client rule told exactly those
  // people "No active LLM provider yet" the moment they logged in and then
  // REFUSED to let them create an agent (validate() below), for a tenant whose
  // chat works. GET /api/providers/availability calls usesPlatformPool
  // directly, so this can no longer drift from what chat actually does.
  // null = not answered yet. A failed call answers `false`, which leaves the
  // two-arm rule below standing - the old behaviour, never a worse one.
  const [platformPoolAvailable, setPlatformPoolAvailable] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .getProviderAvailability()
      .then((a) => {
        if (!cancelled) setPlatformPoolAvailable(!!a?.platformHostedAvailable);
      })
      .catch(() => {
        if (!cancelled) setPlatformPoolAvailable(false);
      });
    return () => {
      cancelled = true;
    };
    // Re-asked when the BYOK list changes (activating/removing a key) AND when
    // the tenant-wide usePlatformLlm flag flips: without the second trigger,
    // "Switch to BYOK" saved the flag but this stale `true` kept onPlatformLlm
    // true, so BYOK never showed as selected until a reload (prod 2026-09-24).
  }, [providers.length, tenantUsePlatformLlm]);

  // "on the managed Platform LLM" - FREE (always), a paid tenant opted in, or
  // a tenant the server says it routes to the pool (a live grant).
  // The rule itself is lib/providerGate.ts (pure, tested). `settled` inside it
  // is a second, smaller fix: the warning used to flash at every login in the
  // gap before tenant settings landed, because an unloaded plan read as
  // "not FREE".
  const gateInput = {
    tenantPlan,
    tenantSettingsError,
    usePlatformLlmToggle: tenantUsePlatformLlm,
    platformPoolAvailable,
    activeProviderCount: activeProviders.length,
  };

  const { onPlatformLlm } = resolveProviderGate({ ...gateInput, routingTier: null });

  // Effective cap for THIS tenant's plan, not the FREE-tier literal: null
  // maxAgents means no cap (UNLIMITED). Falls back to AGENT_LIMIT only on a
  // confirmed settings-fetch failure (fail closed, same posture as isFreePlan
  // above) or before the first response has landed.
  const agentLimit = tenantSettingsError ? AGENT_LIMIT : (maxAgents ?? Infinity);

  const atAgentLimit = agents.length >= agentLimit;

  // Plan-gated controls surface their upsell inline, ON the control itself:
  // gated Buttons use the shared Button's `upgradeLocked`/`upgradeReason`
  // (looks disabled, reveals an Upgrade link on hover), and gated non-button
  // controls (badges) are wrapped in <UpgradeLock>. No separate on-page upgrade
  // buttons or modal — the upgrade link lives in the hover popup.
  // "application should show what platform llm is configured" - direct
  // feedback. Tenant-facing read of the provider/model actually backing
  // FREE-tier (and opted-in paid-tenant) chat - GET /api/providers/platform-llm,
  // safe for any authenticated tenant, unlike the admin-only
  // /api/platform/hosted-llm-providers CRUD this data is sourced from
  // server-side (PlatformHostedLlmProviderService#getActiveSummary).
  // Fetched unconditionally (not just when isFreePlan) since a paid tenant
  // who has flipped on "Use platform-hosted LLM" (ProvidersPanel.tsx)
  // cares just as much.
  const [platformLlmInfo, setPlatformLlmInfo] = useState(null);

  useEffect(() => {
    api
      .platformLlm()
      .then(setPlatformLlmInfo)
      .catch(() => {});
  }, []);

  return {
    onPlatformLlm,
    gateInput,
    atAgentLimit,
    agentLimit,
    tenantDefaultMode,
    setTenantUsePlatformLlm,
    platformLlmInfo,
    isFreePlan,
    tenantSettingsError,
    tenantSettingsNoticeDismissed,
    fetchTenantSettings,
    setTenantSettingsNoticeDismissed,
  };
}
