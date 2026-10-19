// The setup nudge after "we stopped waiting" (2026-09-19 review fix).
//
// A WebLLM download cannot be aborted. When the stall watchdog gives up, the
// banner shows a failure — but the download may still be running, and if it
// finishes the model becomes ready. Before this fix the banner kept showing
// the failure forever, next to a chip showing progress and then "ready".
// These tests pin that the banner follows the shared asset row out of the
// error state, and that it only calls the download "done" once the install
// marker exists (the row also says "ready" for a plain engine warm-up).

import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const MODEL = { id: "model-a", displayName: "Model A", sizeBytes: 1_000_000_000, tier: 1 };
const CAPS = { webgpu: { available: true }, connection: { saveData: false } };

type Listener = (event: { type: string; asset: Record<string, unknown> }) => void;
const listeners = new Set<Listener>();
let cached = false;
const startTier1Download = vi.fn();
const setRoutingMode = vi.fn();

vi.mock("../../lib/edge/capabilities", () => ({
  detectCapabilities: async () => CAPS,
  edgeEnabled: () => true,
  getEdgeRoutingPreference: () => "edge-first",
  selectBestModel: () => ({ kind: "model", model: MODEL, fits: true }),
  EDGE_PREFS_EVENT: "edge-prefs",
}));
vi.mock("../../lib/edge/tier1", () => ({
  fetchTier1Manifest: async () => ({ version: "1", models: [MODEL] }),
  isTier1ModelCached: () => cached,
  startTier1Download: (...args: unknown[]) => startTier1Download(...args),
  tier1DownloadDetails: () => "details",
  tier1Ready: async () => false,
}));
vi.mock("../../lib/edge/downloadManager", () => ({
  assetProgress: (a: { pct?: number }) => ({ pct: a.pct ?? 0 }),
  downloadManager: {
    getAsset: (): null => null,
    subscribe: (fn: Listener) => {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
  },
}));
vi.mock("../../lib/edge/routingState", () => ({ setRoutingMode: (m: string) => setRoutingMode(m) }));

import OnDeviceSetupNudge from "./OnDeviceSetupNudge";
import { describeDownloadFailure } from "../../lib/edge/resilience/failures";

function emit(state: string, extra: Record<string, unknown> = {}) {
  act(() => {
    for (const fn of [...listeners]) fn({ type: "asset", asset: { id: MODEL.id, state, ...extra } });
  });
}

async function failWith(reason: string) {
  startTier1Download.mockResolvedValueOnce({
    ok: false,
    code: "failed",
    detail: reason,
    reason,
    failure: describeDownloadFailure(reason),
  });
  render(<OnDeviceSetupNudge />);
  const button = await screen.findByRole("button", { name: /download|enable|set up|turn on/i });
  fireEvent.click(button);
  await screen.findByText(describeDownloadFailure(reason).title);
}

describe("OnDeviceSetupNudge after a failure", () => {
  beforeEach(() => {
    listeners.clear();
    cached = false;
    startTier1Download.mockReset();
    setRoutingMode.mockReset();
    localStorage.clear();
  });

  it("stalled: says we stopped waiting, offers a retry and the hosted model", async () => {
    await failWith("stalled");
    expect(screen.getByText(/stopped waiting/i)).toBeTruthy();
    expect(screen.queryByText(/it was stopped/i)).toBeNull();
    expect(screen.getByRole("button", { name: "Try again" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Use the hosted model" })).toBeTruthy();
  });

  it("a download that outlived the wait takes the banner back to progress, then to ready", async () => {
    await failWith("stalled");

    emit("downloading", { pct: 61 });
    await waitFor(() => expect(screen.getByText(/Downloading Model A/)).toBeTruthy());
    expect(screen.queryByText(describeDownloadFailure("stalled").title)).toBeNull();

    // "ready" without the install marker is only an engine warm-up: not done yet.
    emit("ready");
    expect(screen.getByText(/Downloading Model A/)).toBeTruthy();

    cached = true;
    emit("verifying");
    emit("ready");
    await waitFor(() => expect(screen.getByText(/is ready/i)).toBeTruthy());
  });

  it("a late failure with nobody awaiting it is shown, not left on a spinner", async () => {
    await failWith("stalled");
    emit("downloading", { pct: 80 });
    await waitFor(() => expect(screen.getByText(/Downloading Model A/)).toBeTruthy());

    emit("error", { failureReason: "incomplete" });
    await waitFor(() => expect(screen.getByText(describeDownloadFailure("incomplete").title)).toBeTruthy());
    expect(screen.getByRole("button", { name: "Use the hosted model" })).toBeTruthy();
  });

  it("rows for other assets never move this banner", async () => {
    await failWith("stalled");
    act(() => {
      for (const fn of [...listeners]) fn({ type: "asset", asset: { id: "some-other-asset", state: "ready" } });
    });
    expect(screen.getByText(describeDownloadFailure("stalled").title)).toBeTruthy();
  });
});
