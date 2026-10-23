// The Consistency check's "run with the agent's tools" switch: the request and stored context it starts, and the
// note about tool use that no longer applies once the samples can use tools.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const useLabRunMock = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock("./useLabRun", () => ({ useLabRun: (...args: unknown[]) => useLabRunMock(...args) }));
vi.mock("./labApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./labApi")>()),
  labApi: {},
}));

import ConsistencyPanel from "./ConsistencyPanel";
import type { ConsistencyContext } from "./consistencyPlan";
import type { LabCapabilities, LabRunRequest } from "./labApi";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "./labFixtures";
import type { UseLabRun } from "./useLabRun";

const ALLOWED: LabCapabilities = {
  allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60,
};

function labOf(extra: Partial<UseLabRun<ConsistencyContext>> = {}): UseLabRun<ConsistencyContext> {
  return {
    run: null, context: null, phase: "idle", error: null, cancelling: false,
    start: vi.fn(async () => {}), cancel: vi.fn(async () => {}), resume: vi.fn(), reset: vi.fn(),
    ...extra,
  };
}

function setup(capabilities: LabCapabilities = ALLOWED, lab = labOf()) {
  useLabRunMock.mockReturnValue(lab);
  render(<ConsistencyPanel agent={FIXTURE_AGENT} turns={FIXTURE_TURNS} capabilities={capabilities} />);
  return lab;
}

const toolsSwitch = () => screen.getByRole("switch", { name: "wtLabel" });
const runButton = () => screen.getByRole("button", { name: "ccRun" });

beforeEach(() => useLabRunMock.mockReset());

describe("ConsistencyPanel with tools", () => {
  it("starts the ordinary request and context with the switch off", () => {
    const lab = setup();
    fireEvent.click(runButton());
    const [request, context] = (lab.start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest, ConsistencyContext];
    expect(request).not.toHaveProperty("withTools");
    expect(context).not.toHaveProperty("withTools");
  });

  it("asks for the agent's tools and records that in the run's context once confirmed on", async () => {
    const lab = setup();
    fireEvent.click(toolsSwitch());
    fireEvent.click(await screen.findByRole("button", { name: "wtConfirm" }));
    await waitFor(() => expect(toolsSwitch()).toHaveAttribute("aria-checked", "true"));
    fireEvent.click(runButton());
    const [request, context] = (lab.start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest, ConsistencyContext];
    expect(request.withTools).toBe(true);
    expect(request.kind).toBe("CONSISTENCY");
    expect(context.withTools).toBe(true);
  });

  it("is disabled with an explanation where the install has the mode off", () => {
    const lab = setup({ ...ALLOWED, withToolsAvailable: false });
    expect(toolsSwitch()).toBeDisabled();
    expect(screen.getByText("wtUnavailable")).toBeInTheDocument();
    fireEvent.click(runButton());
    const [request] = (lab.start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest];
    expect(request).not.toHaveProperty("withTools");
  });

  it("is locked while a run is live", () => {
    setup(ALLOWED, labOf({ phase: "polling" }));
    expect(toolsSwitch()).toBeDisabled();
  });
});
