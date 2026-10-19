import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import type { DependenciesResponse, DependencyEntry, SloResponse } from "../lib/adminApi/reliability";

// AdminShell pulls in auth, nav and the api cache; the page under test only
// needs its title, actions and children.
vi.mock("../components/AdminShell", async () => {
  const React = await import("react");
  return {
    default: ({ title, actions, children }: { title: string; actions?: any; children: any }) =>
      React.createElement("div", null, React.createElement("h1", null, title), actions, children),
  };
});

vi.mock("../lib/adminApi/reliability", () => ({
  reliabilityApi: { dependencies: vi.fn(), slo: vi.fn() },
}));

import { reliabilityApi } from "../lib/adminApi/reliability";
import ReliabilityPage from "./page";

const dependencies = vi.mocked(reliabilityApi.dependencies);
const slo = vi.mocked(reliabilityApi.slo);

function dep(name: string, category: string, status: DependencyEntry["status"], over: Partial<DependencyEntry> = {}): DependencyEntry {
  return {
    name,
    category,
    status,
    latencyMs: 12,
    lastCheckedAt: new Date().toISOString(),
    detail: `${name} detail`,
    ...over,
  };
}

function depsResponse(entries: DependencyEntry[], over: Partial<DependenciesResponse> = {}): DependenciesResponse {
  return {
    generatedAt: new Date().toISOString(),
    cacheTtlSeconds: 30,
    overall: "DEGRADED",
    dependencies: entries,
    notes: ["Hosted-LLM pool rows are checked for reachability only."],
    ...over,
  };
}

function sloResponse(over: Partial<SloResponse> = {}): SloResponse {
  return {
    generatedAt: new Date().toISOString(),
    windowHours: 24,
    coveredSeconds: 86400,
    scope: "replica",
    replicas: 1,
    objectives: { availabilityTarget: 0.995, apiP95TargetMs: 1000, chatTtftP95TargetMs: 4000, latencyQuantile: 0.95 },
    availability: {
      requests: 100000,
      serverErrors: 400,
      availability: 0.996,
      errorRatio: 0.004,
      burnRate: 0.8,
      budgetRemaining: 0.2,
      verdict: "OK",
    },
    latency: [
      {
        key: "other_api",
        label: "Other API requests",
        count: 5000,
        p50Ms: 100,
        p95Ms: 916.7,
        p99Ms: 2125,
        targetMs: 1000,
        meetsTarget: true,
        slowShare: 0.04,
        slowShareBoundMs: 1000,
        burnRate: 0.8,
        verdict: "OK",
      },
    ],
    llmProviders: [{ provider: "OPENAI", calls: 100, errors: 5, errorRate: 0.05 }],
    jobs: { finished: 10, succeeded: 8, failed: 2, successRate: 0.8 },
    notes: ["Availability is the share of server requests that did not end in a 5xx."],
    ...over,
  };
}

const ENTRIES = [
  dep("postgres", "database", "UP"),
  dep("redis", "cache", "DOWN", { detail: "check failed: connection refused" }),
  dep("kafka", "messaging", "UNKNOWN", { latencyMs: null, detail: "workflow events are disabled" }),
];

describe("ReliabilityPage", () => {
  beforeEach(() => {
    dependencies.mockReset();
    slo.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows loading skeletons while both reads are in flight", () => {
    dependencies.mockReturnValue(new Promise<DependenciesResponse>(() => {}));
    slo.mockReturnValue(new Promise<SloResponse>(() => {}));
    render(<ReliabilityPage />);
    expect(screen.getByTestId("deps-loading")).toBeInTheDocument();
    expect(screen.getByTestId("slo-loading")).toBeInTheDocument();
  });

  it("renders every dependency with a status word, not just a colour", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);

    const tiles = await screen.findAllByTestId("dependency-tile");
    expect(tiles).toHaveLength(3);
    const byStatus = Object.fromEntries(tiles.map((t) => [t.getAttribute("data-status"), t]));
    expect(within(byStatus.UP).getByText("Up")).toBeInTheDocument();
    expect(within(byStatus.DOWN).getByText("Down")).toBeInTheDocument();
    expect(byStatus.DOWN).toHaveTextContent("connection refused");
    expect(within(byStatus.UNKNOWN).getByText("Unknown")).toBeInTheDocument();
    expect(byStatus.UNKNOWN).toHaveTextContent("no probe run");

    const overall = screen.getByTestId("deps-overall");
    expect(overall).toHaveAttribute("data-status", "DEGRADED");
    expect(overall).toHaveTextContent("Overall: Degraded");
    expect(overall).toHaveTextContent("1 down");
  });

  it("shows the error and a retry when the dependency read fails with nothing to show", async () => {
    dependencies.mockRejectedValue(new Error("deps boom"));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);
    const alert = await screen.findByTestId("deps-error");
    expect(alert).toHaveTextContent("deps boom");
    expect(alert).not.toHaveTextContent("Showing the last reading");
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("keeps the last dependency reading on screen when a refresh fails, and says so", async () => {
    dependencies.mockResolvedValueOnce(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);
    await screen.findAllByTestId("dependency-tile");
    // the Refresh button is disabled while either read is in flight
    await screen.findByTestId("tile-availability");

    dependencies.mockRejectedValueOnce(new Error("refresh boom"));
    fireEvent.click(screen.getByRole("button", { name: /Refresh/ }));
    const alert = await screen.findByTestId("deps-error");
    expect(alert).toHaveTextContent("refresh boom");
    expect(alert).toHaveTextContent("Showing the last reading below");
    expect(screen.getAllByTestId("dependency-tile")).toHaveLength(3);
  });

  it("shows availability with its objective, burn, remaining budget and verdict", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);

    const tile = await screen.findByTestId("tile-availability");
    expect(tile).toHaveTextContent("99.60%");
    expect(tile).toHaveTextContent("Within budget");
    expect(tile).toHaveTextContent("Objective 99.50%");
    expect(tile).toHaveTextContent("burn 0.80x");
    expect(tile).toHaveTextContent("20% of the error budget left");
    expect(tile).toHaveTextContent("400 server errors");

    const latency = screen.getByTestId("tile-latency-other_api");
    expect(latency).toHaveTextContent("917 ms");
    expect(latency).toHaveTextContent("p50 100 ms");
    expect(latency).toHaveTextContent("Objective: p95 at or under 1.00 s");

    expect(screen.getByTestId("tile-jobs")).toHaveTextContent("80.0%");
    expect(within(screen.getByTestId("providers-table")).getByText("OPENAI")).toBeInTheDocument();
  });

  it("shows n/a, not zero, when there is no data for a series", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse({ availability: null, latency: [], llmProviders: [], jobs: null }));
    render(<ReliabilityPage />);

    const tile = await screen.findByTestId("tile-availability");
    expect(tile).toHaveTextContent("n/a");
    expect(tile).toHaveTextContent("No requests were recorded in this window");
    expect(screen.getByTestId("tile-jobs")).toHaveTextContent("n/a");
    expect(screen.getByTestId("slo-no-latency")).toBeInTheDocument();
    expect(screen.getByTestId("providers-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("tile-latency-other_api")).not.toBeInTheDocument();
  });

  it("says the window is short when the samples span less than was asked", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse({ coveredSeconds: 7200, scope: "platform", replicas: 2 }));
    render(<ReliabilityPage />);
    const scope = await screen.findByTestId("slo-scope");
    expect(scope).toHaveTextContent("Samples span 2.0 h of 24 h");
    expect(scope).toHaveTextContent("All replicas combined (2)");
  });

  it("says plainly when this is only one replica", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);
    expect(await screen.findByTestId("slo-scope")).toHaveTextContent("This replica only");
  });

  it("shows the empty state when no samples exist yet", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse({ replicas: 0, availability: null, latency: [], llmProviders: [], jobs: null, coveredSeconds: 0 }));
    render(<ReliabilityPage />);
    expect(await screen.findByTestId("slo-empty")).toBeInTheDocument();
    expect(screen.queryByTestId("slo-tiles")).not.toBeInTheDocument();
  });

  it("lists the server's notes for both readings", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);
    await screen.findAllByTestId("dependency-tile");
    const lists = await screen.findAllByTestId("notes-list");
    expect(lists).toHaveLength(2);
    expect(lists[0]).toHaveTextContent("reachability only");
    expect(lists[1]).toHaveTextContent("did not end in a 5xx");
  });

  it("re-reads the SLO figures for the window the person picks", async () => {
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);
    await screen.findByTestId("tile-availability");
    expect(slo).toHaveBeenLastCalledWith(24);

    fireEvent.click(screen.getByRole("button", { name: "6 h" }));
    await screen.findByTestId("tile-availability");
    expect(slo).toHaveBeenLastCalledWith(6);
    expect(screen.getByRole("button", { name: "6 h" })).toHaveAttribute("aria-pressed", "true");
  });

  it("auto-refreshes every 30 s while visible and pauses while the tab is hidden", async () => {
    const setIntervalSpy = vi.spyOn(globalThis, "setInterval");
    dependencies.mockResolvedValue(depsResponse(ENTRIES));
    slo.mockResolvedValue(sloResponse());
    render(<ReliabilityPage />);
    await screen.findByTestId("tile-availability");

    const call = setIntervalSpy.mock.calls.find((c) => c[1] === 30_000);
    expect(call).toBeDefined();
    const tick = call![0] as () => void;
    const before = dependencies.mock.calls.length;

    await act(async () => {
      tick();
    });
    expect(dependencies.mock.calls.length).toBe(before + 1);

    Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
    try {
      await act(async () => {
        tick();
      });
      expect(dependencies.mock.calls.length).toBe(before + 1);
    } finally {
      Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
    }
  });
});
