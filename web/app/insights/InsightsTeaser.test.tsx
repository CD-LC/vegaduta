// The home teaser must be invisible unless it has something true to show:
// nothing for a member, nothing on 403 / failure, nothing for zero calls.

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../messages/en.json";
import type { UsageMetricsDto } from "../lib/api/insights";

const insightsApi = vi.hoisted(() => ({ getSpendSummary: vi.fn(), getSpendByDay: vi.fn() }));
const authState = vi.hoisted(() => ({ isAdmin: true, permissions: [] as string[] }));

vi.mock("../lib/api/insights", () => ({ insightsApi }));
vi.mock("../components/AuthProvider", () => ({
  useAuth: () => ({ isAdmin: authState.isAdmin, hasPermission: (name: string) => authState.permissions.includes(name) }),
}));
vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

import InsightsTeaser from "./InsightsTeaser";

const metrics = (over: Partial<UsageMetricsDto> = {}): UsageMetricsDto => ({
  totalCalls: 0,
  errorCount: 0,
  successRate: 0,
  totalTokens: 0,
  messageVolume: 0,
  distinctSessions: 0,
  estimatedCostUsd: 0,
  avgLatencyMs: null,
  latencyP50Ms: null,
  latencyP95Ms: null,
  cacheHitRate: null,
  ...over,
});
const SUMMARY = {
  fromDate: "2026-09-29",
  toDate: "2026-10-05",
  includesLiveToday: true,
  metrics: metrics({ totalCalls: 10, errorCount: 1 }),
};
const BY_DAY = [{ date: "2026-10-04", metrics: metrics({ totalCalls: 10, errorCount: 1 }) }];

beforeEach(() => {
  insightsApi.getSpendSummary.mockReset();
  insightsApi.getSpendByDay.mockReset();
  authState.isAdmin = true;
  authState.permissions = [];
});
afterEach(cleanup);

/** Lets the mocked promises settle, then reports whether anything was rendered. */
async function settledEmpty(container: HTMLElement) {
  await waitFor(() => expect(insightsApi.getSpendSummary).toHaveBeenCalled());
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(container).toBeEmptyDOMElement();
}

describe("InsightsTeaser", () => {
  it("shows a 7-day bar chart, a plain sentence and the link for an admin", async () => {
    insightsApi.getSpendSummary.mockResolvedValue(SUMMARY);
    insightsApi.getSpendByDay.mockResolvedValue(BY_DAY);
    const { container } = render(<InsightsTeaser />);

    expect(await screen.findByText("9 of 10 AI calls succeeded over the last 7 days. Today is still counting.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "AI calls this week" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "See insights" })).toHaveAttribute("href", "/insights");
    expect(container.querySelectorAll("rect[data-bar]")).toHaveLength(7);
    expect(container.querySelectorAll("rect[data-partial='true']")).toHaveLength(1);
    expect(container.innerHTML).not.toMatch(/NaN|insights\.\w+/);

    const range = insightsApi.getSpendSummary.mock.calls[0][0];
    expect((Date.parse(range.to) - Date.parse(range.from)) / 86_400_000).toBe(6);
  });

  it("also shows for a non-admin who holds VIEW_USAGE", async () => {
    authState.isAdmin = false;
    authState.permissions = ["VIEW_USAGE"];
    insightsApi.getSpendSummary.mockResolvedValue(SUMMARY);
    insightsApi.getSpendByDay.mockResolvedValue(BY_DAY);
    render(<InsightsTeaser />);
    expect(await screen.findByTestId("insights-teaser")).toBeInTheDocument();
  });

  it("renders nothing and asks for nothing for a plain member", async () => {
    authState.isAdmin = false;
    const { container } = render(<InsightsTeaser />);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(container).toBeEmptyDOMElement();
    expect(insightsApi.getSpendSummary).not.toHaveBeenCalled();
  });

  it("renders nothing on 403", async () => {
    insightsApi.getSpendSummary.mockRejectedValue(Object.assign(new Error("forbidden"), { status: 403 }));
    const { container } = render(<InsightsTeaser />);
    await settledEmpty(container);
    expect(insightsApi.getSpendByDay).not.toHaveBeenCalled();
  });

  it("renders nothing when either call fails", async () => {
    insightsApi.getSpendSummary.mockResolvedValue(SUMMARY);
    insightsApi.getSpendByDay.mockRejectedValue(new Error("boom"));
    const { container } = render(<InsightsTeaser />);
    await settledEmpty(container);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("renders nothing for a workspace with no calls", async () => {
    insightsApi.getSpendSummary.mockResolvedValue({ ...SUMMARY, metrics: metrics() });
    insightsApi.getSpendByDay.mockResolvedValue([]);
    const { container } = render(<InsightsTeaser />);
    await settledEmpty(container);
  });

  it("renders nothing for a malformed answer", async () => {
    insightsApi.getSpendSummary.mockResolvedValue(null);
    insightsApi.getSpendByDay.mockResolvedValue(null);
    const { container } = render(<InsightsTeaser />);
    await settledEmpty(container);
  });
});
