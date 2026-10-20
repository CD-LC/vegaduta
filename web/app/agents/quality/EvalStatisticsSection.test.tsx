// Render contract for the statistics section — its absence states and, now
// that the client contract is reconciled to the real backend, its wired
// state.
//
// What this locks in: when the section cannot reach real statistics it says
// so in words and renders NO figure at all — and when the three wired client
// methods answer, the pooled per-dataset report actually renders, with the
// repeat counts and intervals that are the whole point of the surface.
//
// The pure decision logic is covered separately in evalStatisticsApi.test.ts.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { I18nProvider } from "../../lib/i18n";
import en from "../../../messages/en.json";

// A mutable stand-in for the shared api client. The two resolvers in this
// folder (resolveEvalStatsApi, resolveEvalApi) feature-detect METHOD NAMES on
// it, so each test controls whether this build "has" the wiring by adding or
// removing functions — the same mechanism the section gates on in production.
const apiMock = vi.hoisted(() => ({} as Record<string, unknown>));
vi.mock("../../lib/api", () => ({ api: apiMock }));

import type { EvalReliabilityReport } from "../../lib/api";
import EvalStatisticsSection from "./EvalStatisticsSection";
import { resolveEvalStatsApi } from "./evalStatisticsApi";

// Every method name the eval-listing resolver checks for.
const EVAL_METHODS = [
  "listEvalDatasets",
  "createEvalDataset",
  "getEvalDataset",
  "updateEvalDataset",
  "deleteEvalDataset",
  "addEvalDatasetCase",
  "updateEvalDatasetCase",
  "deleteEvalDatasetCase",
  "triggerEvalRun",
  "listEvalRuns",
  "getEvalRun",
  "cancelEvalRun",
];

const REPORT: EvalReliabilityReport = {
  agentId: "agent-1",
  generatedAt: "2026-10-03T00:00:00Z",
  confidenceLevel: 0.95,
  maxRunsPerDataset: 10,
  datasets: [
    {
      datasetId: "ds-1",
      datasetName: "Billing FAQ",
      runIds: ["run-2", "run-1"],
      runsConsidered: 2,
      caseCount: 2,
      minRolloutsPerCase: 2,
      maxRolloutsPerCase: 2,
      k: 2,
      unreplicated: false,
      reliabilityGap: 0.31,
      metrics: [
        {
          metric: "MEAN_SCORE",
          label: "mean score",
          question: "How good are the responses on the judge's own scale?",
          value: 0.62,
          interval: { low: 0.48, high: 0.75, confidenceLevel: 0.95, method: "HIERARCHICAL_BOOTSTRAP" },
          n: 3,
          unit: "CASE",
          k: null,
          unreplicated: false,
        },
        {
          metric: "PASS_AT_1",
          label: "pass@1",
          question: "What fraction of individual attempts pass?",
          value: 0.9,
          interval: { low: 0.55, high: 0.99, confidenceLevel: 0.95, method: "WILSON_SCORE" },
          n: 4,
          unit: "ROLLOUT",
          k: null,
          unreplicated: false,
        },
        {
          metric: "PASS_HAT_K",
          label: "pass^k",
          question: "Will it pass all k times running, unattended?",
          value: 0.59,
          interval: null,
          n: 2,
          unit: "CASE",
          k: 2,
          unreplicated: false,
        },
      ],
      cases: [
        {
          caseKey: "case-1",
          userMessage: "Where is my invoice?",
          rollouts: 2,
          successes: 1,
          passRate: 0.5,
          passRateInterval: { low: 0.09, high: 0.91, confidenceLevel: 0.95, method: "WILSON_SCORE" },
          allPassed: false,
          unreplicated: false,
          meanScore: 0.6,
          scoredRollouts: 2,
        },
      ],
      noiseFloor: null,
      warnings: [],
    },
  ],
  warnings: [],
};

function wireStatsApi() {
  for (const m of EVAL_METHODS) apiMock[m] = vi.fn().mockResolvedValue([]);
  apiMock.listEvalRuns = vi.fn().mockResolvedValue([]);
  apiMock.getEvalReliability = vi.fn().mockResolvedValue(REPORT);
  apiMock.getDatasetNoiseFloor = vi.fn().mockResolvedValue({
    datasetId: "ds-1",
    datasetName: "Billing FAQ",
    noiseFloor: null,
    warnings: ["Fewer than two SUCCEEDED runs to measure from."],
  });
  apiMock.compareEvalRunsPaired = vi.fn();
}

beforeEach(() => {
  for (const key of Object.keys(apiMock)) delete apiMock[key];
});

// Select (the dataset picker) translates its own chrome, so renders go
// through the real provider — same wrapper DataPicker.test.tsx uses.
function renderSection(agentId: string | null) {
  return render(
    <I18nProvider locale="en" messages={en} enMessages={en}>
      <EvalStatisticsSection agentId={agentId} />
    </I18nProvider>
  );
}

describe("EvalStatisticsSection — absence states", () => {
  it("renders nothing at all without a selected agent", () => {
    const { container } = renderSection(null);
    expect(container).toBeEmptyDOMElement();
  });

  it("explains the pending api wiring instead of rendering a number", () => {
    // No methods on the client: this is what an older web bundle looks like.
    // The section must say so and must not print a figure.
    expect(resolveEvalStatsApi()).toBeNull();

    renderSection("agent-1");

    expect(screen.getByText(/API client wiring pending/i)).toBeInTheDocument();
    expect(screen.getByText(/Honest eval statistics/i)).toBeInTheDocument();

    // Nothing that could be mistaken for data: no 0.00-style figure and no
    // "n=" repeat count anywhere in the rendered tree.
    expect(document.body.textContent || "").not.toMatch(/\bn=\d/);
    expect(document.body.textContent || "").not.toMatch(/\b\d+\.\d{2}\b/);
  });
});

describe("EvalStatisticsSection — wired against the real backend contract", () => {
  it("detects the three statistics methods on the client", () => {
    wireStatsApi();
    expect(resolveEvalStatsApi()).not.toBeNull();
  });

  it("renders the pooled report: estimates with intervals and repeat counts", async () => {
    wireStatsApi();
    renderSection("agent-1");

    // The dataset reliability block lands once the report resolves. ("Mean
    // judge score" appears twice — the StatCard label and the per-case table
    // column — so assert presence, not uniqueness.)
    expect((await screen.findAllByText("Mean judge score")).length).toBeGreaterThan(0);
    expect(screen.getByText("0.62")).toBeInTheDocument();
    expect(screen.getByText(/n=3/)).toBeInTheDocument();

    // pass@1 and pass^k both render as measurements, plus the pooled-over
    // provenance — two runs contributed one rollout per case each.
    expect(screen.getByText("pass@1")).toBeInTheDocument();
    expect(screen.getByText("pass^2")).toBeInTheDocument();
    expect(screen.getByText(/pooled over 2 runs/)).toBeInTheDocument();

    // The standalone floor endpoint answered "not measured" — rendered as a
    // warning in words, never as a floor of zero.
    // Wait on the endpoint's own warning: the "not measured yet" title also
    // renders while the floor request is still pending (floor === null).
    expect(await screen.findByText(/Fewer than two SUCCEEDED runs/)).toBeInTheDocument();
    expect(screen.getByText(/Noise floor not measured yet/)).toBeInTheDocument();
  });
});
