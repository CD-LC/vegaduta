// Regression tests for the "on-device opt-in" preference-resolution logic
// (routingState.ts's getRoutingMode/setRoutingMode plus the localStorage
// primitives in capabilities.ts and the two IntelligenceSourceControl.tsx
// helpers layered on top of them). Locks in a manual code trace done during
// a live investigation into user confusion about whether "selecting
// on-device" actually persists correctly across a hosted->hybrid round trip.
//
// Style follows the sibling edge/audio/whisper.test.ts + kokoro.test.ts:
// vi.resetModules() + localStorage.clear() + a fresh dynamic import per
// test so each test starts from a genuinely clean module + storage state,
// and NEXT_PUBLIC_EDGE_AGENTS is set per test rather than relying on a
// process-wide default.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TenantProviderState } from "./routingState";

describe("edge/routingState — preference resolution", () => {
  const tenantOnPlatformPool: TenantProviderState = {
    usePlatformLlm: true,
    activeProviderCount: 0,
  };

  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("1. fresh state resolves to hybrid (edge-first by default), not hosted", async () => {
    const { getRoutingMode } = await import("./routingState");
    expect(getRoutingMode(tenantOnPlatformPool)).toBe("hybrid");
  });

  it("2. setRoutingMode('hosted') pins hosted and turns edge opt-in off", async () => {
    const { getRoutingMode, setRoutingMode, getStoredModelOverride, getEdgeOptIn } = await import(
      "./routingState"
    );
    setRoutingMode("hosted");
    expect(getRoutingMode(tenantOnPlatformPool)).toBe("hosted");
    expect(getStoredModelOverride()).toBe("hosted");
    expect(getEdgeOptIn()).toBe(false);
  });

  it("3. hosted -> hybrid round trip clears the hosted pin and recovers hybrid cleanly", async () => {
    const {
      getRoutingMode,
      setRoutingMode,
      getStoredModelOverride,
      getEdgeOptIn,
    } = await import("./routingState");
    const { getEdgeRoutingPreference } = await import("./capabilities");

    setRoutingMode("hosted");
    setRoutingMode("hybrid");

    // The "hosted" pin must reset to "auto" — NOT still read "hosted".
    expect(getStoredModelOverride()).not.toBe("hosted");
    expect(getStoredModelOverride()).toBe("auto");
    expect(getEdgeOptIn()).toBe(true);
    expect(getEdgeRoutingPreference()).toBe("edge-first");
    expect(getRoutingMode(tenantOnPlatformPool)).toBe("hybrid");
  });

  it("4. setRoutingMode('hybrid') does not clobber a pre-existing pinned tier-1 model id", async () => {
    const { setRoutingMode, getStoredModelOverride } = await import("./routingState");
    const { setModelOverride } = await import("./capabilities");

    setModelOverride("some-model-id");
    setRoutingMode("hybrid");

    expect(getStoredModelOverride()).toBe("some-model-id");
  });

  // Bug fix regression (2026-08-07, live user report): "byok is selected,
  // but still edge is responding all requests" — a device that ever had
  // on-device explicitly turned on (edge.optIn="1", a GLOBAL per-device
  // flag, not scoped per agent or per mode) kept satisfying getEdgeOptIn()
  // even after switching this agent to "Your own provider", because
  // setRoutingMode("byok") set routingPref to "ask" but never touched the
  // opt-in flag — and routeTurn() has no special case for "ask" at all, it
  // just falls through to getEdgeOptIn(). See routingState.ts's "byok"
  // case comment for the full mechanism.
  it("7. switching to byok after edge was on turns opt-in off, so getEdgeOptIn() actually goes false", async () => {
    const { setRoutingMode, getRoutingMode, getEdgeOptIn } = await import("./routingState");
    const { getEdgeRoutingPreference } = await import("./capabilities");
    const tenantOnByok: TenantProviderState = { usePlatformLlm: false, activeProviderCount: 1 };

    setRoutingMode("hybrid"); // simulates on-device having been explicitly enabled at some earlier point
    expect(getEdgeOptIn()).toBe(true);

    setRoutingMode("byok");

    expect(getEdgeRoutingPreference()).toBe("ask");
    expect(getEdgeOptIn()).toBe(false); // the actual bug: this used to still read true
    expect(getRoutingMode(tenantOnByok)).toBe("byok");
  });

  // Same mechanism, defensive coverage for the "hosted" branch — not
  // currently reachable via routeTurn() (hosted-only short-circuits before
  // getEdgeOptIn() is ever consulted — see tierRouter.ts), but asserted
  // directly here so a stale opt-in flag can never resurface if that
  // short-circuit ever moves or changes.
  it("8. switching to hosted after edge was on also turns opt-in off", async () => {
    const { setRoutingMode, getEdgeOptIn } = await import("./routingState");

    setRoutingMode("hybrid");
    expect(getEdgeOptIn()).toBe(true);

    setRoutingMode("hosted");

    expect(getEdgeOptIn()).toBe(false);
  });

  // Direct request (2026-08-07): "it should be centrally controlled in edge
  // page, and in every agent page" — this platform has exactly ONE shared
  // set of localStorage preference keys (capabilities.ts), read/written by
  // BOTH the /edge marketplace page's master toggle (edgeConsoleBridge.ts's
  // getEdgeMode/setEdgeMode) and every agent editor's "What powers this
  // agent's replies" picker (routingState.ts's getRoutingMode/
  // setRoutingMode, via IntelligenceSourceControl.tsx) — there is no
  // per-page or per-agent copy to drift out of sync. This test proves that
  // property directly, across BOTH modules, rather than just trusting that
  // "they happen to read the same keys": every mode change made through
  // ONE surface's setter must be visible, correctly, through the OTHER
  // surface's own getter.
  it("9. the /edge page and every agent page agree on mode after every transition (single shared source of truth)", async () => {
    const { setRoutingMode, getRoutingMode } = await import("./routingState");
    const { getEdgeMode } = await import("../../components/edge/edgeConsoleBridge");
    const tenantOnPool: TenantProviderState = { usePlatformLlm: true, activeProviderCount: 0 };
    const tenantOnByok: TenantProviderState = { usePlatformLlm: false, activeProviderCount: 1 };

    // Platform pool (agent page) -> /edge page must show hosted-only.
    setRoutingMode("hosted");
    expect(getRoutingMode(tenantOnPool)).toBe("hosted");
    expect(getEdgeMode()).toBe("hosted-only");

    // On-device (agent page) -> /edge page must show edge-first.
    setRoutingMode("hybrid");
    expect(getRoutingMode(tenantOnPool)).toBe("hybrid");
    expect(getEdgeMode()).toBe("edge-first");

    // Your own provider (agent page) -> /edge page has no distinct "byok"
    // concept (it only knows edge-on vs edge-off), so the correct, honest
    // answer there is hosted-only — the regression this whole fix targets.
    setRoutingMode("byok");
    expect(getRoutingMode(tenantOnByok)).toBe("byok");
    expect(getEdgeMode()).toBe("hosted-only");
  });
});

describe("edge/routingState — IntelligenceSourceControl opt-in helpers", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("5a. getStoredEdgeOptInChoice() returns the exact stored boolean, not null", async () => {
    const { setEdgeOptIn } = await import("./capabilities");
    // Stub tier0/tier1 probing so this test's outcome depends only on the
    // stored preference, never on real browser capability detection (no
    // WebGPU/Prompt API/WASM exists in this jsdom test environment).
    vi.doMock("./tier0", () => ({
      tier0Availability: vi.fn().mockResolvedValue("unavailable"),
      createTier0Session: vi.fn(),
      TIER0_ASSET_ID: "chrome-built-in",
    }));
    vi.doMock("./tier1", () => ({
      tier1Ready: vi.fn().mockResolvedValue(false),
      getTier1RunModel: vi.fn().mockResolvedValue(null),
      fetchTier1Manifest: vi.fn(),
      isTier1ModelCached: vi.fn().mockReturnValue(false),
      startTier1Download: vi.fn(),
    }));

    const { getStoredEdgeOptInChoice } = await import(
      "../../components/agents/IntelligenceSourceControl"
    );

    expect(getStoredEdgeOptInChoice()).toBe(null);

    setEdgeOptIn(true);
    expect(getStoredEdgeOptInChoice()).toBe(true);

    setEdgeOptIn(false);
    expect(getStoredEdgeOptInChoice()).toBe(false);
  });

  it("5b. getEffectiveEdgeOptIn() honors an explicit stored choice directly, without probing tier0/tier1 readiness", async () => {
    const { setEdgeOptIn } = await import("./capabilities");

    const tier0Availability = vi.fn().mockResolvedValue("unavailable");
    const tier1Ready = vi.fn().mockResolvedValue(false);
    vi.doMock("./tier0", () => ({
      tier0Availability,
      createTier0Session: vi.fn(),
      TIER0_ASSET_ID: "chrome-built-in",
    }));
    vi.doMock("./tier1", () => ({
      tier1Ready,
      getTier1RunModel: vi.fn().mockResolvedValue(null),
      fetchTier1Manifest: vi.fn(),
      isTier1ModelCached: vi.fn().mockReturnValue(false),
      startTier1Download: vi.fn(),
    }));

    const { getEffectiveEdgeOptIn } = await import(
      "../../components/agents/IntelligenceSourceControl"
    );

    setEdgeOptIn(true);
    expect(await getEffectiveEdgeOptIn()).toBe(true);

    setEdgeOptIn(false);
    expect(await getEffectiveEdgeOptIn()).toBe(false);

    // The whole point of an EXPLICIT stored choice: it must not need to ask
    // "is a local model actually ready" to answer either case above.
    expect(tier0Availability).not.toHaveBeenCalled();
    expect(tier1Ready).not.toHaveBeenCalled();
  });
});

describe("edge/capabilities — edgeFallbackLabel", () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    sessionStorage.clear();
    process.env.NEXT_PUBLIC_EDGE_AGENTS = "1";
  });

  it("6. every known reason, plus the unknown/default case, returns a distinct non-empty label", async () => {
    const { edgeFallbackLabel } = await import("./capabilities");

    const reasons = [
      "edge-disabled",
      "user-hosted-override",
      "awaiting-user-choice",
      "no-local-tier",
      "aborted",
      "all-tiers-failed",
      "some-unrecognized-reason-string",
    ];

    const labels = reasons.map((reason) => edgeFallbackLabel(reason));

    for (const label of labels) {
      expect(typeof label).toBe("string");
      expect(label.length).toBeGreaterThan(0);
    }

    // "all-tiers-failed" and the unknown case intentionally share the
    // default branch (see capabilities.ts) — every OTHER reason must be
    // distinct from every other label.
    const knownDistinct = labels.slice(0, 6); // excludes the "unrecognized" case
    expect(new Set(knownDistinct).size).toBe(knownDistinct.length);

    // The unrecognized-reason case falls into the same default branch as
    // "all-tiers-failed" by design (switch default) — assert that explicitly
    // rather than assuming it, so this test also documents the behavior.
    expect(edgeFallbackLabel("some-unrecognized-reason-string")).toBe(
      edgeFallbackLabel("all-tiers-failed")
    );
    expect(edgeFallbackLabel(undefined)).toBe(edgeFallbackLabel("all-tiers-failed"));
  });
});
