// Pilot kit UI against a mocked API; the translator reads the real messages/en.json.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { pickOption } from "../components/ui/test-select";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import en from "../../messages/en.json";
import type { BaselineDto } from "../lib/api/roiPilot";

const api = vi.hoisted(() => ({ getBaseline: vi.fn(), setBaseline: vi.fn(), getDemo: vi.fn(), openReport: vi.fn() }));
vi.mock("../lib/api/roiPilot", async (importOriginal) => ({ ...(await importOriginal<typeof import("../lib/api/roiPilot")>()), roiPilotApi: api }));
vi.mock("../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));

import PilotSection, { draftFrom } from "./PilotSection";
import DemoWatermark from "./DemoWatermark";

const empty: BaselineDto = { agentId: "a1", jobType: null, baselinePeriod: null, monthlyVolume: null, avgHandlingMinutes: null, missedRate: null, avgValuePerConversion: null, valueCurrency: null, enteredBy: null, enteredAt: null, source: "tenant-entered" };
const agents = [{ id: "a1", name: "Reminder bot" }, { id: "a2", name: "Lead bot" }];
const range = { from: "2026-09-01", to: "2026-09-30" };

beforeEach(() => {
  Object.values(api).forEach((f) => f.mockReset());
  api.getBaseline.mockResolvedValue(empty);
});
afterEach(cleanup);

describe("draftFrom", () => {
  it("shows a stored missed-rate fraction as a percentage and blanks what was never entered", () => {
    expect(draftFrom({ ...empty, missedRate: 0.225, monthlyVolume: 300 })).toMatchObject({ missedPct: "22.5", volume: "300", minutes: "", value: "" });
    expect(draftFrom(null).missedPct).toBe("");
  });
});

describe("PilotSection", () => {
  it("saves exactly what was typed, converting the percentage to a fraction, and blanks stay null", async () => {
    api.setBaseline.mockResolvedValue({ ...empty, monthlyVolume: 300, missedRate: 0.25, enteredBy: "owner" });
    render(<PilotSection agents={agents} range={range} mayEdit />);
    await waitFor(() => expect(api.getBaseline).toHaveBeenCalledWith("a1"));
    fireEvent.change(screen.getByLabelText(en.roiPilot.volume), { target: { value: "300" } });
    fireEvent.change(screen.getByLabelText(en.roiPilot.missed), { target: { value: "25" } });
    fireEvent.click(screen.getByRole("button", { name: en.roiPilot.save }));
    await waitFor(() => expect(api.setBaseline).toHaveBeenCalled());
    expect(api.setBaseline).toHaveBeenCalledWith("a1", { jobType: null, baselinePeriod: null, monthlyVolume: 300, avgHandlingMinutes: null, missedRate: 0.25, avgValuePerConversion: null, valueCurrency: null });
    expect(await screen.findByText(en.roiPilot.saved)).toBeTruthy();
    expect(screen.getByTestId("pilot-entered-by").textContent).toContain("owner");
  });

  it("rejects a value without a currency and an impossible percentage before calling the server", async () => {
    render(<PilotSection agents={agents} range={range} mayEdit />);
    await waitFor(() => expect(api.getBaseline).toHaveBeenCalled());
    fireEvent.change(screen.getByLabelText(en.roiPilot.value), { target: { value: "1500" } });
    fireEvent.click(screen.getByRole("button", { name: en.roiPilot.save }));
    expect((await screen.findByRole("alert")).textContent).toBe(en.roiPilot.currencyRequired);
    fireEvent.change(screen.getByLabelText(en.roiPilot.missed), { target: { value: "250" } });
    fireEvent.click(screen.getByRole("button", { name: en.roiPilot.save }));
    expect((await screen.findByRole("alert")).textContent).toBe(en.roiPilot.invalid);
    expect(api.setBaseline).not.toHaveBeenCalled();
  });

  it("opens the report for the selected agent and range, and hides Save without MANAGE_BILLING", async () => {
    api.openReport.mockResolvedValue(undefined);
    render(<PilotSection agents={agents} range={range} mayEdit={false} />);
    await waitFor(() => expect(api.getBaseline).toHaveBeenCalled());
    expect(screen.queryByRole("button", { name: en.roiPilot.save })).toBeNull();
    pickOption(en.roiPilot.agent, "Lead bot");
    fireEvent.click(screen.getByRole("button", { name: en.roiPilot.downloadPdf }));
    await waitFor(() => expect(api.openReport).toHaveBeenCalledWith("a2", "pdf", range));
    api.openReport.mockRejectedValue(new Error("HTTP 500"));
    fireEvent.click(screen.getByRole("button", { name: en.roiPilot.openReport }));
    expect((await screen.findByText(en.roiPilot.reportError))).toBeTruthy();
  });
});

describe("PilotSection load failures and limits", () => {
  it("never shows or saves the previous agent's numbers when the next agent's baseline fails to load", async () => {
    api.getBaseline.mockResolvedValueOnce({ ...empty, monthlyVolume: 500, enteredBy: "owner" });
    render(<PilotSection agents={agents} range={range} mayEdit />);
    await waitFor(() => expect((screen.getByLabelText(en.roiPilot.volume) as HTMLInputElement).value).toBe("500"));
    api.getBaseline.mockRejectedValueOnce(new Error("HTTP 500"));
    pickOption(en.roiPilot.agent, "Lead bot");
    expect((await screen.findByRole("alert")).textContent).toBe("HTTP 500");
    expect((screen.getByLabelText(en.roiPilot.volume) as HTMLInputElement).value).toBe("");
    expect(screen.queryByTestId("pilot-entered-by")).toBeNull();
    expect((screen.getByRole("button", { name: en.roiPilot.save }) as HTMLButtonElement).disabled).toBe(true);
    expect(api.setBaseline).not.toHaveBeenCalled();
  });

  it("rejects more decimals than the server stores instead of letting it round them", async () => {
    render(<PilotSection agents={agents} range={range} mayEdit />);
    await waitFor(() => expect(api.getBaseline).toHaveBeenCalled());
    await waitFor(() => expect((screen.getByLabelText(en.roiPilot.missed) as HTMLInputElement).disabled).toBe(false));
    fireEvent.change(screen.getByLabelText(en.roiPilot.missed), { target: { value: "22.555" } });
    fireEvent.click(screen.getByRole("button", { name: en.roiPilot.save }));
    expect((await screen.findByRole("alert")).textContent).toBe(en.roiPilot.tooManyDecimals);
    expect(api.setBaseline).not.toHaveBeenCalled();
  });

  it("explains why the form is read-only without Manage billing", async () => {
    render(<PilotSection agents={agents} range={range} mayEdit={false} />);
    expect((await screen.findByTestId("pilot-read-only")).textContent).toBe(en.roiPilot.readOnly);
  });
});

describe("DemoWatermark", () => {
  it("prints the Example watermark and banner only for a seeded demo workspace", async () => {
    api.getDemo.mockResolvedValue({ demo: true, label: "Example clinic" });
    render(<DemoWatermark />);
    expect(await screen.findByTestId("demo-watermark")).toBeTruthy();
    expect(screen.getByTestId("demo-banner").textContent).toContain("NOT A CUSTOMER RESULT");
    cleanup();
    api.getDemo.mockResolvedValue({ demo: false, label: null });
    render(<DemoWatermark />);
    await waitFor(() => expect(api.getDemo).toHaveBeenCalledTimes(2));
    expect(screen.queryByTestId("demo-watermark")).toBeNull();
  });

  it("renders nothing when the probe fails", async () => {
    api.getDemo.mockRejectedValue(new Error("x"));
    render(<DemoWatermark />);
    await waitFor(() => expect(api.getDemo).toHaveBeenCalled());
    expect(screen.queryByTestId("demo-watermark")).toBeNull();
  });
});
