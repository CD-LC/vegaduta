// Prompt Knockout's "run with the agent's tools" switch: the request it starts, and what the user sees where the
// install does not allow the mode. useLabRun, the HTTP client and the api barrel are mocked as in the other
// KnockoutPanel tests; the toggle and the panel are the real components.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";

const hoisted = vi.hoisted(() => ({
  lab: null as unknown,
  useLabRunArgs: [] as unknown[][],
  listEvalDatasets: vi.fn(),
  getEvalDataset: vi.fn(),
}));

vi.mock("../../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock("./useLabRun", () => ({
  useLabRun: (...args: unknown[]) => {
    hoisted.useLabRunArgs.push(args);
    return hoisted.lab;
  },
}));
vi.mock("./labApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./labApi")>()),
  labApi: {},
}));
vi.mock("../../../lib/api", () => ({
  api: {
    listEvalDatasets: (...args: unknown[]) => hoisted.listEvalDatasets(...args),
    getEvalDataset: (...args: unknown[]) => hoisted.getEvalDataset(...args),
  },
}));

import { ALLOWED, labOf, renderPanel, resetKnockoutMocks, runButton, useProbes } from "./knockoutTestKit";
import type { KnockoutContext } from "./knockoutPlan";
import type { LabRunRequest } from "./labApi";
import type { UseLabRun } from "./useLabRun";

const currentLab = () => hoisted.lab as UseLabRun<KnockoutContext>;
const toolsSwitch = () => screen.getByRole("switch", { name: "wtLabel" });

async function turnToolsOn() {
  fireEvent.click(toolsSwitch());
  fireEvent.click(await screen.findByRole("button", { name: "wtConfirm" }));
  await waitFor(() => expect(toolsSwitch()).toHaveAttribute("aria-checked", "true"));
}

beforeEach(() => resetKnockoutMocks(hoisted));

describe("KnockoutPanel with tools", () => {
  it("starts the ordinary tool-less request when the switch is left off", async () => {
    await renderPanel();
    useProbes("Where is my order?\nCan I get a refund?");
    expect(toolsSwitch()).toHaveAttribute("aria-checked", "false");
    fireEvent.click(runButton());
    const [request] = (currentLab().start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest];
    expect(request).not.toHaveProperty("withTools");
  });

  it("asks for the agent's tools once the switch is confirmed on", async () => {
    await renderPanel();
    useProbes("Where is my order?\nCan I get a refund?");
    await turnToolsOn();
    fireEvent.click(runButton());
    const [request] = (currentLab().start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest];
    expect(request.withTools).toBe(true);
    expect(request.kind).toBe("KNOCKOUT");
  });

  it("shows the switch disabled, with the reason, where the install has the mode off", async () => {
    await renderPanel({ capabilities: { ...ALLOWED, withToolsAvailable: false } });
    useProbes("Where is my order?\nCan I get a refund?");
    expect(toolsSwitch()).toBeDisabled();
    expect(screen.getByText("wtUnavailable")).toBeInTheDocument();
    fireEvent.click(runButton());
    const [request] = (currentLab().start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest];
    expect(request).not.toHaveProperty("withTools");
  });

  it("falls back to the explanation when the server refuses a run for tools", async () => {
    const { rerender } = await renderPanel();
    useProbes("Where is my order?\nCan I get a refund?");
    await turnToolsOn();
    hoisted.lab = labOf("error", {
      error: "Running the lab with the agent's tools is turned off on this install (app.agent-lab.with-tools.enabled).",
    });
    const { default: KnockoutPanel } = await import("./KnockoutPanel");
    const { FIXTURE_AGENT } = await import("./labFixtures");
    rerender(<KnockoutPanel agent={FIXTURE_AGENT} capabilities={ALLOWED} />);
    await screen.findByTestId("lab-tools-refused");
    expect(toolsSwitch()).toBeDisabled();
    expect(toolsSwitch()).toHaveAttribute("aria-checked", "false");
  });
});
