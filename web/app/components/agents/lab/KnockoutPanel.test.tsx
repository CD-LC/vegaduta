// Prompt Knockout panel: what it will spend is stated before anything runs,
// every reason the run cannot start is visible (never a 400 from the
// server), a workspace with no eval datasets - FREE plan 403, or the
// preview's `{}` - degrades to probe questions without an unhandled
// rejection, and results render from the run's own stored context, partial
// runs included. useLabRun, the HTTP client and the api barrel are mocked;
// labApi keeps its real constants and plannedCalls.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";

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

import {
  ALLOWED,
  LOCKED,
  RUN_CONTEXT,
  SECTIONS,
  labOf,
  renderPanel,
  resetKnockoutMocks,
  runButton,
  runOf,
  useProbes,
} from "./knockoutTestKit";
import { buildKnockoutRequest, type KnockoutContext } from "./knockoutPlan";
import { promptWithout } from "./promptSections";
import { FIXTURE_AGENT } from "./labFixtures";
import type { UseLabRun } from "./useLabRun";

const currentLab = () => hoisted.lab as UseLabRun<KnockoutContext>;

beforeEach(() => resetKnockoutMocks(hoisted));

describe("KnockoutPanel form", () => {
  it("runs on the agent's knockout slot and lists the prompt's sections, all ticked", async () => {
    await renderPanel();
    expect(hoisted.useLabRunArgs[0]).toEqual([FIXTURE_AGENT.id, "KNOCKOUT"]);
    expect(screen.getByText("koIntro")).toBeInTheDocument();
    const boxes = screen.getAllByRole("checkbox");
    expect(boxes).toHaveLength(3);
    boxes.forEach((box) => expect(box).toBeChecked());
    expect(screen.getByRole("checkbox", { name: /^Role/ })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /^Tone/ })).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: /^Escalation/ })).toBeInTheDocument();
    expect(screen.getByText(`koSectionChars:{"count":${SECTIONS[0].text.length}}`)).toBeInTheDocument();
    expect(screen.queryByText(/koSectionsMerged/)).not.toBeInTheDocument();
  });

  it("expands a section's text on request", async () => {
    await renderPanel();
    const toggle = screen.getByRole("button", { name: "showMore: Tone" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(screen.getByRole("button", { name: "showLess: Tone" })).toHaveAttribute("aria-expanded", "true");
  });

  it("says so, and offers no form, when the agent has no prompt", async () => {
    await renderPanel({ agent: { ...FIXTURE_AGENT, systemPrompt: "  \n", instructions: null } });
    expect(screen.getByText("koNoPrompt")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "koRun" })).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("falls back to the agent's instructions when it has no system prompt", async () => {
    await renderPanel({ agent: { ...FIXTURE_AGENT, systemPrompt: null, instructions: FIXTURE_AGENT.systemPrompt } });
    expect(screen.getAllByRole("checkbox")).toHaveLength(3);
  });

  it("tells the user when a long prompt's parts were merged", async () => {
    const prompt = Array.from({ length: 16 }, (_, i) => `Rule ${i + 1}: ${"x".repeat(60)}`).join("\n\n");
    await renderPanel({ agent: { ...FIXTURE_AGENT, systemPrompt: prompt } });
    expect(screen.getAllByRole("checkbox")).toHaveLength(12);
    expect(screen.getByText('koSectionsMerged:{"max":12}')).toBeInTheDocument();
  });

  it("states the number of model calls before anything runs", async () => {
    await renderPanel();
    useProbes("Where is my order?\nCan I get a refund?\n\n   ");
    // 2 questions x (3 baseline repeats + 3 sections) = 12 answers, each judged.
    expect(screen.getByText('estimateCalls:{"count":24} koBreakdown:{"answers":12,"judges":12}')).toBeInTheDocument();
    expect(runButton()).toBeEnabled();
    expect(screen.getByText("koProbesNote")).toBeInTheDocument();

    // Leaving a section out of the test removes its cells.
    fireEvent.click(screen.getByRole("checkbox", { name: /^Tone/ }));
    expect(screen.getByText('estimateCalls:{"count":20} koBreakdown:{"answers":10,"judges":10}')).toBeInTheDocument();

    // More baseline repeats cost more.
    fireEvent.change(screen.getByLabelText("koRepeatsLabel"), { target: { value: "5" } });
    expect(screen.getByText('estimateCalls:{"count":28} koBreakdown:{"answers":14,"judges":14}')).toBeInTheDocument();
  });

  it("keeps the repeats inside 2 to 5", async () => {
    await renderPanel();
    const repeats = screen.getByLabelText("koRepeatsLabel");
    expect(repeats).toHaveValue(3);
    fireEvent.change(repeats, { target: { value: "9" } });
    expect(repeats).toHaveValue(5);
    fireEvent.change(repeats, { target: { value: "1" } });
    expect(repeats).toHaveValue(2);
    fireEvent.change(repeats, { target: { value: "" } });
    expect(repeats).toHaveValue(2);
  });

  it("uses only the first 10 probe questions and says so", async () => {
    await renderPanel();
    useProbes(Array.from({ length: 12 }, (_, i) => `question ${i + 1}`).join("\n"));
    expect(screen.getByText('koItemsTrimmed:{"max":10}')).toBeInTheDocument();
    // 10 x (3 + 3) = 60 cells.
    expect(screen.getByText(/estimateCalls:\{"count":120\}/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(runButton()).toBeEnabled();
  });

  it("disables Run, with the limit stated, when the plan is over the cap", async () => {
    await renderPanel();
    useProbes(Array.from({ length: 10 }, (_, i) => `question ${i + 1}`).join("\n"));
    fireEvent.change(screen.getByLabelText("koRepeatsLabel"), { target: { value: "5" } });
    // 10 x (5 + 3) = 80 cells = 160 calls.
    expect(screen.getByText(/estimateCalls:\{"count":160\}/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent('estimateOverCap:{"cap":120}');
    expect(runButton()).toBeDisabled();
    fireEvent.click(runButton());
    expect(currentLab().start).not.toHaveBeenCalled();
  });

  it("takes the cap from the server's capabilities", async () => {
    await renderPanel({ capabilities: { ...ALLOWED, maxCallsPerRun: 20 } });
    useProbes("q1\nq2");
    expect(screen.getByRole("alert")).toHaveTextContent('estimateOverCap:{"cap":20}');
    expect(runButton()).toBeDisabled();
  });

  it("shows the Run button upgrade-locked on a plan that may not run", async () => {
    await renderPanel({ capabilities: LOCKED });
    useProbes("q1\nq2");
    const button = runButton();
    expect(button).toHaveAttribute("data-upgrade-locked");
    expect(button).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(button);
    expect(currentLab().start).not.toHaveBeenCalled();
  });

  it("cannot run against a server without the lab routes", async () => {
    await renderPanel({ capabilities: null });
    useProbes("q1\nq2");
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", "capabilitiesUnsupported");
    // Falls back to the default cap for the estimate.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("starts the run with the planned request and the context to read it back", async () => {
    await renderPanel();
    useProbes("Where is my order?\nCan I get a refund?");
    fireEvent.click(screen.getByRole("checkbox", { name: /^Tone/ }));
    fireEvent.click(runButton());

    const start = currentLab().start as ReturnType<typeof vi.fn>;
    expect(start).toHaveBeenCalledTimes(1);
    const [request, context] = start.mock.calls[0];
    expect(context).toEqual({
      sections: SECTIONS,
      testedSectionIds: ["s1", "s3"],
      items: [
        { message: "Where is my order?", expected: null },
        { message: "Can I get a refund?", expected: null },
      ],
      repeats: 3,
      prompt: FIXTURE_AGENT.systemPrompt,
    });
    expect(request).toEqual(buildKnockoutRequest(context as KnockoutContext));
    expect(request).toMatchObject({ kind: "KNOCKOUT", scoring: "JUDGE", similarity: "NONE" });
    expect(request.systemPrompts).toEqual([
      promptWithout(SECTIONS, []),
      promptWithout(SECTIONS, ["s1"]),
      promptWithout(SECTIONS, ["s3"]),
    ]);
    expect(request.cells.map((cell: { key: string }) => cell.key)).toEqual([
      "b0:i0", "b1:i0", "b2:i0", "s1:i0", "s3:i0",
      "b0:i1", "b1:i1", "b2:i1", "s1:i1", "s3:i1",
    ]);
    // The context is what useLabRun keeps in sessionStorage.
    expect(JSON.parse(JSON.stringify(context))).toEqual(context);
  });

  it("cannot start a second run while one is live", async () => {
    hoisted.lab = labOf("polling", { run: runOf("RUNNING", []), context: RUN_CONTEXT });
    await renderPanel();
    useProbes("q1\nq2");
    expect(runButton()).toBeDisabled();
    expect(screen.getByRole("status")).toHaveTextContent("statusRunning");
    expect(screen.getByRole("button", { name: "cancel" })).toBeInTheDocument();
  });

  it("cannot start a second run over one that is lost (it may still be going)", async () => {
    hoisted.lab = labOf("lost", { run: runOf("RUNNING", []), context: RUN_CONTEXT });
    await renderPanel();
    useProbes("q1\nq2");
    expect(runButton()).toBeDisabled();
    fireEvent.click(runButton());
    expect(currentLab().start).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("statusLost");
    expect(screen.getByRole("button", { name: "resume" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "cancel" })).toBeInTheDocument();
  });
});