// Insights page against a mocked API. The translator is backed by the real
// messages/en.json, so the assertions read the sentences a person would see
// and a key missing from en.json fails here as its raw path.

import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import en from "../../messages/en.json";
import type { AgentJobCountsDto, UsageMetricsDto } from "../lib/api/insights";

const insightsApi = vi.hoisted(() => ({
  getSpendSummary: vi.fn(),
  getSpendByDay: vi.fn(),
  getSpendByModel: vi.fn(),
  getSpendByAgent: vi.fn(),
  getAgentJobCounts: vi.fn(),
}));
const api = vi.hoisted(() => ({ listAgents: vi.fn() }));
const authState = vi.hoisted(() => ({ isAdmin: true, permissions: ["VIEW_USAGE", "VIEW_AUDIT_LOG"] as string[] }));

vi.mock("../lib/api/insights", () => ({
  insightsApi,
  isForbidden: (error: unknown) => (error as { status?: number } | null)?.status === 403,
}));
vi.mock("../lib/api", () => ({ api }));
vi.mock("../components/AppShell", () => ({
  default: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));
vi.mock("../components/RouteTransition", () => ({ usePageReady: (): void => undefined }));
vi.mock("../components/AuthProvider", () => ({
  useAuth: () => ({ isAdmin: authState.isAdmin, hasPermission: (name: string) => authState.permissions.includes(name) }),
}));
vi.mock("next/navigation", () => ({ usePathname: () => "/insights" }));
vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

import InsightsPage from "./page";

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

const ZERO_SUMMARY = { fromDate: "2026-09-29", toDate: "2026-10-05", includesLiveToday: true, metrics: metrics() };
const BUSY_SUMMARY = {
  ...ZERO_SUMMARY,
  metrics: metrics({ totalCalls: 10, errorCount: 1, estimatedCostUsd: 5.5, latencyP50Ms: 420, latencyP95Ms: 2100 }),
};
const ZERO_JOBS: AgentJobCountsDto = {
  queued: 0,
  running: 0,
  awaitingApproval: 0,
  live: 0,
  succeededInWindow: 0,
  failedInWindow: 0,
  cancelledInWindow: 0,
  window: "PT168H",
  windowStart: "2026-09-28T10:00:00Z",
  asOf: "2026-10-05T10:00:00Z",
  agentId: null,
  tenantWide: true,
};
const BUSY_JOBS = { ...ZERO_JOBS, queued: 2, running: 1, awaitingApproval: 1, succeededInWindow: 9, failedInWindow: 1, cancelledInWindow: 3 };

const forbidden = () => Object.assign(new Error("You don't have permission to do that"), { status: 403 });

function mockAdminData() {
  insightsApi.getSpendSummary.mockResolvedValue(BUSY_SUMMARY);
  insightsApi.getSpendByDay.mockResolvedValue([
    { date: "2026-09-30", metrics: metrics({ totalCalls: 8, errorCount: 1, estimatedCostUsd: 4.5 }) },
    { date: "2026-10-05", metrics: metrics({ totalCalls: 2, estimatedCostUsd: 1 }) },
  ]);
  insightsApi.getSpendByModel.mockResolvedValue(
    Array.from({ length: 7 }, (_, i) => ({ model: `model-${i}`, modality: "text", metrics: metrics({ totalCalls: i + 1, estimatedCostUsd: i + 1 }) }))
  );
  insightsApi.getSpendByAgent.mockResolvedValue([
    { agentId: "a1", metrics: metrics({ totalCalls: 7, estimatedCostUsd: 4 }) },
    { agentId: "feedfacecafebeef", metrics: metrics({ totalCalls: 2, estimatedCostUsd: 1 }) },
    { agentId: null, metrics: metrics({ totalCalls: 1, estimatedCostUsd: 0.5 }) },
  ]);
  insightsApi.getAgentJobCounts.mockResolvedValue(BUSY_JOBS);
  api.listAgents.mockResolvedValue([{ id: "a1", name: "Support bot" }]);
}

beforeEach(() => {
  for (const fn of [...Object.values(insightsApi), api.listAgents]) fn.mockReset();
  api.listAgents.mockResolvedValue([]);
  authState.isAdmin = true;
  authState.permissions = ["VIEW_USAGE", "VIEW_AUDIT_LOG"];
});
afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe("InsightsPage - zero-data workspace", () => {
  beforeEach(() => {
    insightsApi.getSpendSummary.mockResolvedValue(ZERO_SUMMARY);
    insightsApi.getSpendByDay.mockResolvedValue([]);
    insightsApi.getSpendByModel.mockResolvedValue([]);
    insightsApi.getSpendByAgent.mockResolvedValue([]);
    insightsApi.getAgentJobCounts.mockResolvedValue(ZERO_JOBS);
  });

  it("shows empty states with a next step, never an empty chart", async () => {
    const { container } = render(<InsightsPage />);
    expect(await screen.findByText("No AI activity in this period")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Go to agents" })).toHaveAttribute("href", "/agents");
    expect(await screen.findByText("No jobs yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Start a job" })).toHaveAttribute("href", "/jobs");
    expect(screen.getByText(/no job finished in the last 7 days/)).toBeInTheDocument();

    expect(container.querySelector("svg[role='img']")).toBeNull();
    expect(screen.queryByRole("button", { name: "View as table" })).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(container.innerHTML).not.toMatch(/NaN|Infinity|insights\.\w+|charts\.\w+/);
  });

  it("renders the success rate as '-', not 0% or 100%", async () => {
    render(<InsightsPage />);
    expect(await screen.findByTestId("insights-success-rate")).toHaveTextContent(/^-$/);
    const kpis = screen.getByTestId("insights-kpis");
    expect(within(kpis).queryByText(/\d%/)).not.toBeInTheDocument();
    expect(within(kpis).getByText("$0.00")).toBeInTheDocument();
    expect(within(kpis).getByText("0")).toBeInTheDocument();
  });
});

describe("InsightsPage - admin with data", () => {
  beforeEach(mockAdminData);

  it("asks for the last 7 days by default and shows the range the server echoed", async () => {
    render(<InsightsPage />);
    await screen.findByTestId("insights-kpis");
    const range = insightsApi.getSpendSummary.mock.calls[0][0];
    expect(range.from).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect((Date.parse(range.to) - Date.parse(range.from)) / 86_400_000).toBe(6);
    expect(insightsApi.getSpendByDay).toHaveBeenCalledWith(range);
    expect(insightsApi.getAgentJobCounts).toHaveBeenCalledWith({ window: "P7D" });
    expect(screen.getByTestId("insights-range")).toHaveTextContent("Showing 29 Sep to 5 Oct (UTC dates). Today is still in progress.");
  });

  it("shows the KPI row with an estimated cost and approximate latency", async () => {
    render(<InsightsPage />);
    const kpis = await screen.findByTestId("insights-kpis");
    expect(within(kpis).getByText("10")).toBeInTheDocument();
    expect(within(kpis).getByText("1 failed")).toBeInTheDocument();
    expect(screen.getByTestId("insights-success-rate")).toHaveTextContent("90%");
    expect(within(kpis).getByText("9 of 10 AI calls.")).toBeInTheDocument();
    expect(within(kpis).getByText("Estimated cost")).toBeInTheDocument();
    expect(within(kpis).getByText("$5.50")).toBeInTheDocument();
    expect(within(kpis).getByText("~420 ms")).toBeInTheDocument();
    expect(within(kpis).getByText(/longer than ~2\.1 s \(p95\)/)).toBeInTheDocument();
    expect(within(kpis).getByRole("link", { name: "See plan usage" })).toHaveAttribute("href", "/usage");
    expect(within(kpis).getByRole("link", { name: "See issues" })).toHaveAttribute("href", "/admin/issues");
  });

  it("hides the issues link from someone who can not open it", async () => {
    authState.permissions = ["VIEW_USAGE"];
    render(<InsightsPage />);
    await screen.findByTestId("insights-kpis");
    expect(screen.queryByRole("link", { name: "See issues" })).not.toBeInTheDocument();
  });

  it("gives every chart a plain sentence and marks today as 'so far'", async () => {
    const { container } = render(<InsightsPage />);
    expect(await screen.findByText("9 of 10 AI calls succeeded from 29 Sep to 5 Oct. Today is still counting.")).toBeInTheDocument();
    expect(screen.getByText("AI calls cost an estimated $5.50 from 29 Sep to 5 Oct. Today is still counting.")).toBeInTheDocument();

    const partial = container.querySelectorAll("rect[data-partial='true']");
    expect(partial).toHaveLength(2); // calls per day + estimated cost per day
    expect(partial[0].querySelector("title")?.textContent).toBe("5 Oct: 2 calls (so far)");
    expect(partial[1].querySelector("title")?.textContent).toBe("5 Oct: $1.00 estimated (so far)");
    // 7 zero-filled days per chart.
    expect(container.querySelectorAll("rect[data-bar]")).toHaveLength(14);
    expect(screen.getAllByText("Today (the hatched last bar) is still in progress, not a full day.")).toHaveLength(2);
  });

  it("ranks agents and models by estimated cost, capped at 5 with 'and N more'", async () => {
    render(<InsightsPage />);
    expect(
      await screen.findByText("Support bot has the highest estimated cost: $4.00 of $5.50 in total (3 ranked).")
    ).toBeInTheDocument();
    expect(screen.getByText("Agent feedface")).toBeInTheDocument();
    expect(screen.getByText("Not from an agent")).toBeInTheDocument();
    expect(screen.getByText("$4.00 estimated, 7 calls")).toBeInTheDocument();

    // The model total covers all 7 rows even though only 5 are drawn.
    expect(screen.getByText("model-6 has the highest estimated cost: $7.00 of $28.00 in total (7 ranked).")).toBeInTheDocument();
    expect(screen.getByText("and 2 more")).toBeInTheDocument();
    expect(screen.queryByText("model-0")).not.toBeInTheDocument();
  });

  it("offers the same data as a table", async () => {
    render(<InsightsPage />);
    await screen.findByText("AI calls per day");
    const toggles = screen.getAllByRole("button", { name: "View as table" });
    expect(toggles).toHaveLength(5); // 2 day charts, 2 rankings, jobs
    fireEvent.click(toggles[0]);
    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("row")).toHaveLength(8);
    expect(within(table).getByText("5 Oct (so far)")).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Estimated cost" })).toBeInTheDocument();
  });

  it("draws the job status bar for the whole workspace with the server's window", async () => {
    const { container } = render(<InsightsPage />);
    expect(
      await screen.findByText("9 jobs succeeded and 1 failed in the last 7 days; 4 are in progress now.")
    ).toBeInTheDocument();
    expect(screen.getByTestId("insights-jobs-scope")).toHaveTextContent("Whole workspace");
    expect(container.querySelectorAll("rect[data-segment]")).toHaveLength(5);
    for (const label of ["Queued", "Running", "Awaiting approval", "Succeeded", "Failed"]) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
    expect(screen.getByText("3 cancelled in the same window are not shown.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open Jobs" })).toHaveAttribute("href", "/jobs");
  });

  it("switching to 90 days refetches, and the job window shown is the clamped one", async () => {
    render(<InsightsPage />);
    await screen.findByTestId("insights-kpis");
    insightsApi.getAgentJobCounts.mockResolvedValue({ ...BUSY_JOBS, windowStart: "2026-09-05T10:00:00Z", window: "PT720H" });

    fireEvent.click(screen.getByRole("button", { name: "90 days" }));
    expect(screen.getByRole("button", { name: "90 days" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "7 days" })).toHaveAttribute("aria-pressed", "false");

    await waitFor(() => expect(insightsApi.getAgentJobCounts).toHaveBeenLastCalledWith({ window: "P90D" }));
    const range = insightsApi.getSpendSummary.mock.calls.at(-1)![0];
    expect((Date.parse(range.to) - Date.parse(range.from)) / 86_400_000).toBe(89);
    expect(
      await screen.findByText("9 jobs succeeded and 1 failed in the last 30 days; 4 are in progress now.")
    ).toBeInTheDocument();
  });
});

describe("InsightsPage - non-admin (403 on spend reports)", () => {
  beforeEach(() => {
    authState.isAdmin = false;
    authState.permissions = [];
    insightsApi.getSpendSummary.mockRejectedValue(forbidden());
    insightsApi.getAgentJobCounts.mockResolvedValue({ ...BUSY_JOBS, tenantWide: false });
  });

  it("shows only the jobs section, labelled 'Your agents', with an admin-only note and no error", async () => {
    render(<InsightsPage />);
    expect(await screen.findByTestId("insights-admin-only")).toHaveTextContent(/visible to workspace admins only/);
    expect(await screen.findByTestId("insights-jobs-scope")).toHaveTextContent("Your agents");
    expect(screen.getByText("Background jobs for the agents you can see, not the whole workspace.")).toBeInTheDocument();
    expect(screen.getByText("9 jobs succeeded and 1 failed in the last 7 days; 4 are in progress now.")).toBeInTheDocument();

    expect(screen.queryByTestId("insights-kpis")).not.toBeInTheDocument();
    expect(screen.queryByText("AI calls per day")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });

  it("never asks for workspace usage without the permission (a refusal is written to the audit log)", async () => {
    render(<InsightsPage />);
    await screen.findByTestId("insights-admin-only");
    expect(insightsApi.getSpendSummary).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "30 days" }));
    await waitFor(() => expect(insightsApi.getAgentJobCounts).toHaveBeenLastCalledWith({ window: "P30D" }));
    expect(insightsApi.getSpendSummary).not.toHaveBeenCalled();
  });

  it("server says no despite the client-side permission: refused once, not even after a period change", async () => {
    authState.permissions = ["VIEW_USAGE"];
    render(<InsightsPage />);
    await screen.findByTestId("insights-admin-only");
    expect(insightsApi.getSpendSummary).toHaveBeenCalledTimes(1);
    expect(insightsApi.getSpendByDay).not.toHaveBeenCalled();
    expect(insightsApi.getSpendByModel).not.toHaveBeenCalled();
    expect(insightsApi.getSpendByAgent).not.toHaveBeenCalled();
    expect(api.listAgents).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "30 days" }));
    await waitFor(() => expect(insightsApi.getAgentJobCounts).toHaveBeenLastCalledWith({ window: "P30D" }));
    expect(insightsApi.getSpendSummary).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("insights-admin-only")).toBeInTheDocument();
  });
});

describe("InsightsPage - errors", () => {
  it("spend failure: inline retry, jobs still render, and retry recovers", async () => {
    insightsApi.getSpendSummary.mockRejectedValueOnce(Object.assign(new Error("boom"), { status: 500 }));
    insightsApi.getAgentJobCounts.mockResolvedValue(BUSY_JOBS);
    render(<InsightsPage />);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("We could not load the usage numbers.");
    expect(screen.queryByTestId("insights-admin-only")).not.toBeInTheDocument();
    expect(await screen.findByText(/9 jobs succeeded and 1 failed/)).toBeInTheDocument();

    mockAdminData();
    fireEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId("insights-kpis")).toBeInTheDocument();
    expect(screen.queryByText("We could not load the usage numbers.")).not.toBeInTheDocument();
  });

  it("a breakdown failing after the summary is still an inline error, not a blank page", async () => {
    mockAdminData();
    insightsApi.getSpendByModel.mockRejectedValue(new Error("boom"));
    render(<InsightsPage />);
    expect(await screen.findByText("We could not load the usage numbers.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Insights" })).toBeInTheDocument();
  });

  it("jobs failure: inline retry inside the card, spend still renders, and retry recovers", async () => {
    mockAdminData();
    insightsApi.getAgentJobCounts.mockReset();
    insightsApi.getAgentJobCounts.mockRejectedValueOnce(new Error("boom"));
    render(<InsightsPage />);

    expect(await screen.findByText("We could not load the job counts.")).toBeInTheDocument();
    expect(await screen.findByTestId("insights-kpis")).toBeInTheDocument();
    expect(screen.queryByTestId("insights-jobs-scope")).not.toBeInTheDocument();

    insightsApi.getAgentJobCounts.mockResolvedValue(BUSY_JOBS);
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText(/9 jobs succeeded and 1 failed/)).toBeInTheDocument();
    expect(screen.queryByText("We could not load the job counts.")).not.toBeInTheDocument();
  });

  it("both failing: two inline retries and the page chrome, never blank", async () => {
    insightsApi.getSpendSummary.mockRejectedValue(new Error("boom"));
    insightsApi.getAgentJobCounts.mockRejectedValue(new Error("boom"));
    render(<InsightsPage />);
    await waitFor(() => expect(screen.getAllByRole("button", { name: "Try again" })).toHaveLength(2));
    expect(screen.getByRole("heading", { name: "Insights" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "7 days" })).toBeInTheDocument();
  });

  it("an agent-name lookup failure does not break the ranking", async () => {
    mockAdminData();
    api.listAgents.mockRejectedValue(new Error("boom"));
    render(<InsightsPage />);
    expect(await screen.findByText("Agent a1 has the highest estimated cost: $4.00 of $5.50 in total (3 ranked).")).toBeInTheDocument();
  });
});

describe("InsightsPage - /roi links", () => {
  beforeEach(() => mockAdminData());

  it("links to /roi on a shared install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    const { container } = render(<InsightsPage />);
    await screen.findByTestId("insights-kpis");
    expect(container.querySelectorAll("a[href='/roi']").length).toBeGreaterThanOrEqual(2);
  });

  it("has no link to the billing pages on a personal install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    const { container } = render(<InsightsPage />);
    await screen.findByTestId("insights-kpis");
    expect(container.querySelectorAll("a[href='/roi']")).toHaveLength(0);
    expect(container.querySelector("a[href='/usage']")).not.toBeNull();
  });
});
