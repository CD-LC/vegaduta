// ROI page against a mocked API. The translator is backed by the real messages/en.json, so assertions read the
// sentences a person would see and a key missing from en.json fails here as its raw path.
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import en from "../../messages/en.json";
import type { AgentRoiResponseDto, AgentRoiRowDto, BudgetDto, PriceDto, PriceListDto } from "../lib/api/roi";

const roiApi = vi.hoisted(() => ({
  getAgents: vi.fn(),
  getBudget: vi.fn(),
  getPrices: vi.fn(),
  setPrice: vi.fn(),
  setAssumption: vi.fn(),
  downloadAgentsCsv: vi.fn(),
}));
const authState = vi.hoisted(() => ({ isAdmin: false, permissions: ["VIEW_USAGE", "MANAGE_BILLING"] as string[] }));

const pilotApi = vi.hoisted(() => ({
  getBaseline: vi.fn(),
  setBaseline: vi.fn(),
  getDemo: vi.fn(),
  openReport: vi.fn(),
}));
vi.mock("../lib/api/roiPilot", async (importOriginal) => ({ ...(await importOriginal<typeof import("../lib/api/roiPilot")>()), roiPilotApi: pilotApi }));
vi.mock("../lib/api/roi", async (importOriginal) => ({ ...(await importOriginal<typeof import("../lib/api/roi")>()), roiApi }));
vi.mock("../components/AppShell", () => ({ default: ({ children }: { children?: ReactNode }) => <div>{children}</div> }));
vi.mock("../components/RouteTransition", () => ({ usePageReady: (): void => undefined }));
vi.mock("../components/AuthProvider", () => ({
  useAuth: () => ({ isAdmin: authState.isAdmin, hasPermission: (name: string) => authState.permissions.includes(name) }),
}));
vi.mock("next/navigation", () => ({ usePathname: () => "/roi" }));
vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

import RoiPage from "./page";

const row = (over: Partial<AgentRoiRowDto> = {}): AgentRoiRowDto => ({
  agentId: "a1", agentName: "Support bot", conversations: 4, resolved: 2, resolvedConfirmed: 2, resolvedAssumed: 0, resolvedByHuman: 0,
  handedToHuman: 1, abandoned: 1, unresolved: 0, humanCorrected: 2, handoverRate: 0.25, humanCorrectionRate: 0.5,
  approvalsDecided: 3, approvalsDenied: 1, approvalDenialRate: 0.3333, byokEstimatedCostUsd: 3, platformHostedEstimatedCostUsd: 0,
  estimatedCostUsd: 3, estimatedCostPerResolvedUsd: 1.5, costComplete: true, platformChargesMinor: 0, platformChargesCurrency: null,
  valuePerResolution: 5, valueCurrency: "USD", minutesSavedPerResolution: 10, valueTotal: 10, minutesSavedTotal: 20, netValue: 7,
  roiPercent: 233.3, roiNote: "OK", ...over,
});
const table = (agents: AgentRoiRowDto[], includeAssumed = false): AgentRoiResponseDto => ({
  from: "2026-09-15", to: "2026-10-14", includeAssumedResolutions: includeAssumed, roiBasis: "based on your assumptions", agents,
});
const budget = (over: Partial<BudgetDto> = {}): BudgetDto => ({
  monthStart: "2026-10-01", plan: "PREMIUM", spentUsd: 170, capUsd: 200, percentUsed: 85, capConfigured: true, spendBasis: "x", alertsFired: [80], ...over,
});
const prices = (over: Partial<PriceListDto> = {}): PriceListDto => ({ prices: [], unpricedModels: [], currency: "USD", ...over });

function load(t: AgentRoiResponseDto, b: BudgetDto = budget(), p: PriceListDto = prices()) {
  roiApi.getAgents.mockResolvedValue(t);
  roiApi.getBudget.mockResolvedValue(b);
  roiApi.getPrices.mockResolvedValue(p);
}

beforeEach(() => {
  pilotApi.getDemo.mockResolvedValue({ demo: false, label: null });
  pilotApi.getBaseline.mockResolvedValue({ agentId: "a1", jobType: null, baselinePeriod: null, monthlyVolume: null, avgHandlingMinutes: null, missedRate: null, avgValuePerConversion: null, valueCurrency: null, enteredBy: null, enteredAt: null, source: "tenant-entered" });
  authState.isAdmin = false;
  authState.permissions = ["VIEW_USAGE", "MANAGE_BILLING"];
  Object.values(roiApi).forEach((f) => f.mockReset());
});
afterEach(cleanup);

describe("RoiPage", () => {
  it("shows the per-agent table with estimated cost, rates and ROI labelled as the tenant's assumptions", async () => {
    load(table([row()]));
    render(<RoiPage />);
    const r = await screen.findByTestId("roi-row-a1");
    expect(within(r).getByText("Support bot")).toBeTruthy();
    expect(within(r).getByText("$3.00")).toBeTruthy();
    expect(within(r).getByText("$1.5000")).toBeTruthy();
    expect(within(r).getByText("25%")).toBeTruthy();
    expect(within(r).getByText("50%")).toBeTruthy();
    expect(within(r).getByText("33.3%")).toBeTruthy();
    const cell = within(r).getByTestId("roi-cell");
    expect(cell.textContent).toContain("233.3%");
    expect(cell.textContent).toContain("based on your assumptions");
    expect(screen.getByText("Cost (estimated)")).toBeTruthy();
  });

  it("keeps platform charges and the platform-pool estimate apart from the estimate for the tenant's own keys", async () => {
    load(table([row({ byokEstimatedCostUsd: 3, platformHostedEstimatedCostUsd: 1, estimatedCostUsd: 4, platformChargesMinor: 12345, platformChargesCurrency: "INR" })]));
    render(<RoiPage />);
    const r = await screen.findByTestId("roi-row-a1");
    expect(within(r).getByTestId("roi-cost-split").textContent).toBe("your keys $3.00 \u00b7 platform pool $1.00");
    expect(within(r).getByTestId("roi-charges").textContent).toBe("123.45 INR");
    expect(screen.getByText("Platform charges")).toBeTruthy();
  });

  it("shows no platform charges as a dash and hides the split when the pool was not used", async () => {
    load(table([row()]));
    render(<RoiPage />);
    const r = await screen.findByTestId("roi-row-a1");
    expect(within(r).getByTestId("roi-charges").textContent).toBe("\u2014");
    expect(within(r).queryByTestId("roi-cost-split")).toBeNull();
  });

  it("shows dashes, not zeros, for an agent with no outcomes and no assumptions", async () => {
    load(table([row({ conversations: 0, resolved: 0, handoverRate: null, humanCorrectionRate: null, approvalDenialRate: null, estimatedCostPerResolvedUsd: null, valuePerResolution: null, valueCurrency: null, minutesSavedPerResolution: null, valueTotal: null, minutesSavedTotal: null, netValue: null, roiPercent: null, roiNote: "NO_ASSUMPTIONS" })]));
    render(<RoiPage />);
    const r = await screen.findByTestId("roi-row-a1");
    expect(within(r).getAllByText("—").length).toBeGreaterThanOrEqual(4);
    expect(within(r).getByTestId("roi-cell-none").textContent).toBe(en.roi.roiNoteNoAssumptions);
    expect(within(r).queryByText("0%")).toBeNull();
  });

  it("marks an incomplete cost as a lower bound", async () => {
    load(table([row({ costComplete: false })]));
    render(<RoiPage />);
    const r = await screen.findByTestId("roi-row-a1");
    expect(r.textContent).toContain("≥ $3.00");
  });

  it("draws the budget bar in the warning tone at 85% and in the over tone at 100%", async () => {
    load(table([row()]));
    const { unmount } = render(<RoiPage />);
    expect((await screen.findByTestId("roi-budget-fill")).getAttribute("data-tone")).toBe("warn");
    expect(screen.getByTestId("roi-budget-percent").textContent).toBe("85%");
    unmount();
    load(table([row()]), budget({ spentUsd: 210, percentUsed: 105, alertsFired: [80, 100] }));
    render(<RoiPage />);
    const fill = await screen.findByTestId("roi-budget-fill");
    expect(fill.getAttribute("data-tone")).toBe("over");
    expect(fill.getAttribute("style")).toContain("width: 100%");
  });

  it("says there is no budget instead of drawing a bar when the plan has no cap", async () => {
    load(table([row()]), budget({ capConfigured: false, capUsd: null, percentUsed: null }));
    render(<RoiPage />);
    expect(await screen.findByTestId("roi-budget-none")).toBeTruthy();
    expect(screen.queryByTestId("roi-budget-fill")).toBeNull();
  });

  it("refetches with the chosen range and the idle-close toggle, and exports the same filter", async () => {
    load(table([row()]));
    render(<RoiPage />);
    await screen.findByTestId("roi-table");
    fireEvent.click(screen.getByRole("button", { name: "7 days" }));
    await waitFor(() => expect(roiApi.getAgents.mock.calls.length).toBeGreaterThan(1));
    const first = roiApi.getAgents.mock.calls[roiApi.getAgents.mock.calls.length - 1][0];
    expect(first.includeAssumed).toBe(false);
    fireEvent.click(screen.getByRole("switch", { name: en.roi.includeAssumed }));
    await waitFor(() => expect(roiApi.getAgents.mock.calls[roiApi.getAgents.mock.calls.length - 1][0].includeAssumed).toBe(true));
    fireEvent.click(screen.getByTestId("roi-export"));
    await waitFor(() => expect(roiApi.downloadAgentsCsv).toHaveBeenCalledWith(expect.objectContaining({ includeAssumed: true })));
  });

  it("rejects an inverted date range locally without calling the API", async () => {
    load(table([row()]));
    render(<RoiPage />);
    await screen.findByTestId("roi-table");
    const calls = roiApi.getAgents.mock.calls.length;
    fireEvent.change(screen.getByLabelText(en.roi.from), { target: { value: "2026-12-31" } });
    expect((await screen.findByTestId("roi-range-error")).textContent).toBe(en.roi.rangeOrder);
    expect(roiApi.getAgents.mock.calls.length).toBe(calls);
  });

  it("saves assumptions exactly as typed: blank stays null, no default", async () => {
    load(table([row({ valuePerResolution: null, valueCurrency: null, minutesSavedPerResolution: null, valueTotal: null, minutesSavedTotal: null, netValue: null, roiPercent: null, roiNote: "NO_ASSUMPTIONS" })]));
    roiApi.setAssumption.mockResolvedValue({});
    render(<RoiPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit value assumptions for Support bot" }));
    const editor = await screen.findByTestId("roi-assumption-editor");
    expect((within(editor).getByLabelText(en.roi.assumptionValue) as HTMLInputElement).value).toBe("");
    fireEvent.change(within(editor).getByLabelText(en.roi.assumptionMinutes), { target: { value: "12" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(roiApi.setAssumption).toHaveBeenCalledWith("a1", { valuePerResolution: null, valueCurrency: null, minutesSavedPerResolution: 12 }));
  });

  it("requires a currency with a value and rejects a negative number", async () => {
    load(table([row()]));
    render(<RoiPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Edit value assumptions for Support bot" }));
    const editor = await screen.findByTestId("roi-assumption-editor");
    fireEvent.change(within(editor).getByLabelText(en.roi.assumptionCurrencyLabel), { target: { value: "" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    expect((await within(editor).findByRole("alert")).textContent).toBe(en.roi.assumptionCurrency);
    fireEvent.change(within(editor).getByLabelText(en.roi.assumptionValue), { target: { value: "-3" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(within(editor).getByRole("alert").textContent).toBe(en.roi.assumptionInvalid));
    expect(roiApi.setAssumption).not.toHaveBeenCalled();
  });

  it("lists unpriced models and lets a billing manager set a price; viewers get no controls", async () => {
    const unpriced = prices({ unpricedModels: [{ providerName: "OPENAI", model: "gpt-x", calls: 12, totalTokens: 100, reason: "NO_PRICE" }] });
    load(table([row()]), budget(), unpriced);
    roiApi.setPrice.mockResolvedValue({});
    const { unmount } = render(<RoiPage />);
    expect((await screen.findByTestId("roi-unpriced")).textContent).toContain("gpt-x");
    fireEvent.click(screen.getByRole("button", { name: "Set price" }));
    const form = await screen.findByTestId("roi-price-form");
    fireEvent.change(within(form).getByLabelText(en.roi.priceInput), { target: { value: "1.5" } });
    fireEvent.change(within(form).getByLabelText(en.roi.priceOutput), { target: { value: "6" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(roiApi.setPrice).toHaveBeenCalledWith({ providerName: "OPENAI", modelPrefix: "gpt-x", inputPerMillion: 1.5, outputPerMillion: 6, cacheWritePerMillion: null, cacheReadPerMillion: null }));
    unmount();

    authState.permissions = ["VIEW_USAGE"];
    load(table([row()]), budget(), unpriced);
    render(<RoiPage />);
    await screen.findByTestId("roi-unpriced");
    expect(screen.queryByRole("button", { name: "Set price" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Edit value assumptions/ })).toBeNull();
  });

  it("lets a billing manager update an existing price, starting from the stored rates", async () => {
    const stored: PriceDto = { id: "p1", providerName: "ANTHROPIC", modelPrefix: "claude", inputPerMillion: 3, outputPerMillion: 15, cacheWritePerMillion: null, cacheReadPerMillion: 0.3, effectiveFrom: "2026-01-01", setAt: "2026-01-01T00:00:00Z", setBy: "u", note: null };
    load(table([row()]), budget(), prices({ prices: [stored] }));
    roiApi.setPrice.mockResolvedValue({});
    render(<RoiPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Update the price for claude" }));
    const form = await screen.findByTestId("roi-price-form");
    expect((within(form).getByLabelText(en.roi.priceInput) as HTMLInputElement).value).toBe("3");
    expect((within(form).getByLabelText(en.roi.priceCacheWrite) as HTMLInputElement).value).toBe("");
    fireEvent.change(within(form).getByLabelText(en.roi.priceInput), { target: { value: "4" } });
    fireEvent.click(within(form).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(roiApi.setPrice).toHaveBeenCalledWith({ providerName: "ANTHROPIC", modelPrefix: "claude", inputPerMillion: 4, outputPerMillion: 15, cacheWritePerMillion: null, cacheReadPerMillion: 0.3 }));
  });

  it("asks nothing of the API and explains why without VIEW_USAGE", async () => {
    authState.permissions = [];
    render(<RoiPage />);
    expect(await screen.findByTestId("roi-no-access")).toBeTruthy();
    expect(roiApi.getAgents).not.toHaveBeenCalled();
  });

  it("shows a retryable error when loading fails", async () => {
    roiApi.getAgents.mockRejectedValue(new Error("boom"));
    roiApi.getBudget.mockResolvedValue(budget());
    roiApi.getPrices.mockResolvedValue(prices());
    render(<RoiPage />);
    expect((await screen.findByRole("alert")).textContent).toContain(en.roi.loadError);
    load(table([row()]));
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByTestId("roi-table")).toBeTruthy();
  });
});
