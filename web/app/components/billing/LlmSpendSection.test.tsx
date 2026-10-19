// LlmSpendSection - the AI model spend block on /admin/billing (moved there
// from Platform Maintenance, 2026-09-24). Pins the three states a tenant
// admin can land in: FREE plan (backend 403 -> honest Premium notice wired
// to the page's Upgrade action, never a raw error string), a workspace with
// real spend (headline tiles, per-provider rows ordered by spend, model mix,
// call log with the failure row + Diagnose), and a workspace with no calls.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const getLlmCalls = vi.fn();
const getLlmUsageSummary = vi.fn();
const getLlmTelemetry = vi.fn();
const diagnoseLlmCall = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    getLlmCalls: (...args: unknown[]) => getLlmCalls(...args),
    getLlmUsageSummary: () => getLlmUsageSummary(),
    getLlmTelemetry: () => getLlmTelemetry(),
    diagnoseLlmCall: (id: string) => diagnoseLlmCall(id),
  },
}));
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
// The console-wide Select is a motion-animated popover; a native select is
// enough to drive the status filter here.
vi.mock("../Select", () => ({
  default: ({ value, onChange, options, ...rest }: any) => (
    <select value={value} onChange={onChange} data-testid={rest["data-testid"]}>
      {options.map((o: { value: string; label: string }) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  ),
}));

import LlmSpendSection from "./LlmSpendSection";

function http(status: number, message = "nope"): Error & { status: number } {
  const e = new Error(message) as Error & { status: number };
  e.status = status;
  return e;
}

const SUMMARY = [
  { providerName: "OPENAI", totalCalls: 10, errorCount: 0, totalTokens: 1000, estimatedCostUsd: 0.01, firstCallAt: "2026-09-01T00:00:00Z", lastCallAt: "2026-09-24T00:00:00Z" },
  { providerName: "ANTHROPIC", totalCalls: 40, errorCount: 4, totalTokens: 9000, estimatedCostUsd: 0.09, firstCallAt: "2026-09-02T00:00:00Z", lastCallAt: "2026-09-24T01:00:00Z" },
  { providerName: "PLATFORM_HOSTED", totalCalls: 5, errorCount: 0, totalTokens: 500, estimatedCostUsd: null, firstCallAt: null, lastCallAt: null },
];

const CALLS = [
  { id: "c1", providerName: "ANTHROPIC", model: "claude-sonnet-5", totalTokens: 120, estimatedCostUsd: 0.00042, success: true, errorMessage: null as string | null, latencyMs: 850, startedAt: "2026-09-24T09:00:00Z" },
  { id: "c2", providerName: "ANTHROPIC", model: "claude-sonnet-5", totalTokens: null, estimatedCostUsd: null, success: false, errorMessage: "429 rate limited", latencyMs: 1500, startedAt: "2026-09-24T08:59:00Z" },
];

const TELEMETRY = {
  callsLast24h: 12,
  costTodayUsd: 0.0123,
  costPerConversationTodayUsd: 0.0041,
  conversationsToday: 3,
  modelSplitLast24h: [
    { model: "claude-sonnet-5", calls: 9, percent: 75 },
    { model: "gpt-5", calls: 3, percent: 25 },
  ],
};

describe("LlmSpendSection", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    getLlmCalls.mockReset();
    getLlmUsageSummary.mockReset();
    getLlmTelemetry.mockReset();
    diagnoseLlmCall.mockReset();
    // The $ surfaces only exist where billing is on (the hosted posture).
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    delete window.__ENV;
  });

  it("FREE plan: a 403 renders the Premium notice and its button opens the page's upgrade flow", async () => {
    getLlmCalls.mockRejectedValue(http(403, "Premium feature"));
    getLlmUsageSummary.mockRejectedValue(http(403, "Premium feature"));
    getLlmTelemetry.mockResolvedValue(TELEMETRY);
    const onUpgrade = vi.fn();

    render(<LlmSpendSection canUpgrade onUpgrade={onUpgrade} />);

    await screen.findByText("premiumLockedTitle");
    expect(screen.queryByText("Premium feature")).toBeNull();
    fireEvent.click(screen.getByText("premiumLockedCta"));
    expect(onUpgrade).toHaveBeenCalledTimes(1);
    // Nothing else from the data view leaks into the locked state.
    expect(screen.queryByTestId("llm-spend-tiles")).toBeNull();
  });

  it("FREE plan on the top tier: the notice shows without an upgrade button", async () => {
    getLlmCalls.mockRejectedValue(http(403));
    getLlmUsageSummary.mockRejectedValue(http(403));
    getLlmTelemetry.mockRejectedValue(new Error("older core"));

    render(<LlmSpendSection canUpgrade={false} onUpgrade={() => {}} />);

    await screen.findByText("premiumLockedTitle");
    expect(screen.queryByText("premiumLockedCta")).toBeNull();
  });

  it("with spend: tiles, providers by spend, model mix, call log and the failure row", async () => {
    getLlmCalls.mockResolvedValue(CALLS);
    getLlmUsageSummary.mockResolvedValue(SUMMARY);
    getLlmTelemetry.mockResolvedValue(TELEMETRY);
    diagnoseLlmCall.mockResolvedValue({
      category: "RATE_LIMIT",
      title: "Provider rate limit",
      explanation: "Too many requests.",
      suggestedFix: "Retry with backoff.",
      suggestedModels: ["claude-haiku-4-5"],
      source: "RULE",
    });

    render(<LlmSpendSection canUpgrade onUpgrade={() => {}} />);

    // Headline tiles - totals summed from the provider summary (0.01 +
    // 0.09, null ignored), today's figures from telemetry.
    const tiles = await screen.findByTestId("llm-spend-tiles");
    expect(within(tiles).getByTestId("llm-spend-tile-total")).toHaveTextContent("$0.1000");
    expect(within(tiles).getByTestId("llm-spend-tile-today")).toHaveTextContent("$0.0123");
    expect(within(tiles).getByTestId("llm-spend-tile-today")).toHaveTextContent('"perConversation":"$0.0041"');
    expect(within(tiles).getByTestId("llm-spend-tile-calls")).toHaveTextContent("55");
    expect(within(tiles).getByTestId("llm-spend-tile-calls")).toHaveTextContent('"errors":"4","rate":"7.3"');
    expect(within(tiles).getByTestId("llm-spend-tile-tokens")).toHaveTextContent("10,500");

    // Providers ordered by spend, highest first; the internal
    // PLATFORM_HOSTED label never reaches the screen.
    const rows = screen.getAllByTestId(/^llm-spend-provider-/);
    expect(rows.map((r) => r.getAttribute("data-testid"))).toEqual([
      "llm-spend-provider-ANTHROPIC",
      "llm-spend-provider-OPENAI",
      "llm-spend-provider-PLATFORM_HOSTED",
    ]);
    expect(rows[0]).toHaveTextContent("90%");
    expect(rows[2]).toHaveTextContent("VegaDūta (included)");
    expect(screen.queryByText("PLATFORM_HOSTED")).toBeNull();

    // Model mix from telemetry.
    const mix = screen.getByTestId("llm-spend-model-mix");
    expect(within(mix).getByText("claude-sonnet-5")).toBeInTheDocument();
    expect(within(mix).getByText("gpt-5")).toBeInTheDocument();

    // Call log: the fetch asks for the full 200-row window, the failed row
    // carries its error and a working Diagnose button.
    expect(getLlmCalls).toHaveBeenCalledWith("all", 200);
    expect(screen.getByText("$0.000420")).toBeInTheDocument();
    expect(screen.getByText("429 rate limited")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("llm-call-diagnose-c2"));
    await screen.findByText("Provider rate limit");
    expect(diagnoseLlmCall).toHaveBeenCalledWith("c2");
    expect(screen.getByText("claude-haiku-4-5")).toBeInTheDocument();

    // Switching the filter re-queries with that status.
    fireEvent.change(screen.getByTestId("llm-usage-status-filter-select"), { target: { value: "error" } });
    await waitFor(() => expect(getLlmCalls).toHaveBeenCalledWith("error", 200));
  });

  it("no calls yet: says so, with no tiles or table", async () => {
    getLlmCalls.mockResolvedValue([]);
    getLlmUsageSummary.mockResolvedValue([]);
    getLlmTelemetry.mockResolvedValue({ ...TELEMETRY, modelSplitLast24h: null });

    render(<LlmSpendSection canUpgrade onUpgrade={() => {}} />);

    await screen.findByText("noCallsLoggedYet");
    expect(screen.queryByTestId("llm-spend-tiles")).toBeNull();
    expect(screen.queryByTestId("llm-usage-status-filter-select")).toBeNull();
  });

  it("a non-403 failure shows the error text, not the Premium notice", async () => {
    getLlmCalls.mockRejectedValue(http(500, "boom"));
    getLlmUsageSummary.mockResolvedValue(SUMMARY);
    getLlmTelemetry.mockResolvedValue(null);

    render(<LlmSpendSection canUpgrade onUpgrade={() => {}} />);

    await screen.findByText("boom");
    expect(screen.queryByText("premiumLockedTitle")).toBeNull();
  });
});
