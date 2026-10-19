// The Consistency check panel: what it offers to sample, the call estimate it
// states before anything is spent, every way the Run button can be gated, the
// exact request it starts, and how a finished run reads - above all that
// missing verdicts come out as "Inconclusive", never as disagreement.
//
// The run hook, the API object and the translator are mocked; labApi's
// constants and plannedCalls are the real ones, so the estimate asserted here
// is the formula the server enforces.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { choose, optionsOf, selectedIndex } from "./selectTestUtils";
import { fireEvent, render, screen } from "@testing-library/react";
import fs from "node:fs";
import path from "node:path";
import en from "../../../../messages/en.json";

const useLabRunMock = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock("./useLabRun", () => ({ useLabRun: (...args: unknown[]) => useLabRunMock(...args) }));
vi.mock("./labApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./labApi")>();
  return {
    ...actual,
    labApi: {
      getLabCapabilities: vi.fn(),
      startLabRun: vi.fn(),
      getLabRun: vi.fn(),
      cancelLabRun: vi.fn(),
      rewindChatSession: vi.fn(),
      probeSessionEntryCount: vi.fn(),
    },
  };
});

import ConsistencyPanel from "./ConsistencyPanel";
import type { ConsistencyContext } from "./consistencyPlan";
import { LAB_MAX_MESSAGE_CHARS } from "./labApi";
import type { LabCapabilities, LabCellRequest, LabCellResult, LabMessage, LabRun, LabRunRequest, LabSimilarity } from "./labApi";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "./labFixtures";
import type { ConsistencyPanelProps, LabTurn } from "./types";
import type { LabRunPhase, UseLabRun } from "./useLabRun";

const ALLOWED: LabCapabilities = {
  allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60,
};
const LOCKED: LabCapabilities = { ...ALLOWED, allowed: false, reason: "PLAN" };

function labOf(phase: LabRunPhase = "idle", extra: Partial<UseLabRun<ConsistencyContext>> = {}): UseLabRun<ConsistencyContext> {
  return {
    run: null, context: null, phase, error: null, cancelling: false,
    start: vi.fn(async () => {}),
    cancel: vi.fn(async () => {}),
    resume: vi.fn(),
    reset: vi.fn(),
    ...extra,
  };
}

function cell(key: string, status: LabCellResult["status"] = "DONE"): LabCellResult {
  return {
    key, status,
    answer: status === "DONE" ? `Answer from ${key}` : null, answerTruncated: false,
    score: null, scoreStatus: "NONE", scoreFeedback: null,
    errorMessage: status === "ERROR" ? "The model call timed out." : null, ms: 800,
  };
}

function runOf(extra: Partial<LabRun> = {}): LabRun {
  return {
    runId: "run-1", agentId: FIXTURE_AGENT.id, kind: "CONSISTENCY", status: "SUCCEEDED",
    plannedCalls: 15, completedCalls: 15, cancelRequested: false,
    errorMessage: null, createdAtMs: 1, updatedAtMs: 1, finishedAtMs: 1,
    cells: [], similarity: null,
    ...extra,
  };
}

/** A finished run whose samples fall into the given groups (one entry per sample). */
function groupedRun(groups: number[], extra: Partial<LabRun> = {}): LabRun {
  const keys = groups.map((_, n) => `k${n}`);
  const similarity: LabSimilarity = {
    method: "JUDGE", status: "DONE", keys,
    matrix: groups.map((a) => groups.map((b) => (a === b ? 1 : 0))),
  };
  return runOf({ cells: keys.map((key) => cell(key)), similarity, ...extra });
}

const CONTEXT: ConsistencyContext = { message: "What are your support hours?", history: [], k: 5, usedTools: false };

function setup(overrides: Partial<ConsistencyPanelProps> = {}, lab: UseLabRun<ConsistencyContext> = labOf()) {
  useLabRunMock.mockReturnValue(lab);
  const props: ConsistencyPanelProps = { agent: FIXTURE_AGENT, turns: FIXTURE_TURNS, capabilities: ALLOWED, ...overrides };
  return { lab, ...render(<ConsistencyPanel {...props} />) };
}

const runButton = () => screen.getByRole("button", { name: "ccRun" });
const turnSelect = () => screen.getByRole("combobox");
const samplesInput = () => screen.getByRole("spinbutton") as HTMLInputElement;

beforeEach(() => {
  useLabRunMock.mockReset();
});

describe("ConsistencyPanel form", () => {
  it("drives a CONSISTENCY run for this agent", () => {
    setup();
    expect(useLabRunMock).toHaveBeenCalledWith(FIXTURE_AGENT.id, "CONSISTENCY");
    expect(screen.getByText("ccIntro")).toBeInTheDocument();
    // The scope note belongs to AgentLab, above the panel - never twice.
    expect(screen.queryByText("scopeNote")).not.toBeInTheDocument();
  });

  it("offers the server turns and starts on the latest one, with its history ticked", () => {
    setup();
    expect(screen.getByRole("radio", { name: "ccSourceTurn" })).toBeChecked();
    const options = optionsOf(turnSelect());
    expect(options).toHaveLength(2);
    expect(options[1]).toHaveTextContent('graphTurnOption:{"n":2,"preview":"Thanks. What are your support hours?"}');
    expect(selectedIndex(turnSelect())).toBe(1);
    expect(screen.getByRole("checkbox", { name: 'ccIncludeHistory:{"count":2}' })).toBeChecked();
  });

  it("states 15 model calls for the default 5 samples, with the breakdown", () => {
    setup();
    expect(samplesInput().value).toBe("5");
    expect(screen.getByText(/estimateCalls:\{"count":15\} ccBreakdown:\{"samples":5,"comparisons":10\}/)).toBeInTheDocument();
    expect(runButton()).toBeEnabled();
  });

  it("re-estimates when the sample count changes, and keeps it inside 3..8", () => {
    setup();
    fireEvent.change(samplesInput(), { target: { value: "8" } });
    expect(screen.getByText(/estimateCalls:\{"count":36\} ccBreakdown:\{"samples":8,"comparisons":28\}/)).toBeInTheDocument();
    fireEvent.change(samplesInput(), { target: { value: "40" } });
    expect(screen.getByText(/estimateCalls:\{"count":36\}/)).toBeInTheDocument();
    fireEvent.blur(samplesInput());
    expect(samplesInput().value).toBe("8");
    fireEvent.change(samplesInput(), { target: { value: "1" } });
    expect(screen.getByText(/estimateCalls:\{"count":6\} ccBreakdown:\{"samples":3,"comparisons":3\}/)).toBeInTheDocument();
    fireEvent.change(samplesInput(), { target: { value: "" } });
    expect(screen.getByText(/estimateCalls:\{"count":15\}/)).toBeInTheDocument();
    fireEvent.blur(samplesInput());
    expect(samplesInput().value).toBe("5");
  });

  it("caps the sample count at the server's similarity limit", () => {
    setup({ capabilities: { ...ALLOWED, maxSimilarityCells: 4 } });
    expect(samplesInput()).toHaveAttribute("max", "4");
    expect(screen.getByText(/estimateCalls:\{"count":10\} ccBreakdown:\{"samples":4,"comparisons":6\}/)).toBeInTheDocument();
  });

  it("starts the run with k identical cells, the stored prompt and the conversation before the turn", () => {
    const { lab } = setup();
    fireEvent.click(runButton());
    expect(lab.start).toHaveBeenCalledTimes(1);
    const [request, context] = (lab.start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest, ConsistencyContext];
    const history: LabMessage[] = [
      { role: "user", content: FIXTURE_TURNS[0].content },
      { role: "assistant", content: FIXTURE_TURNS[1].content },
    ];
    expect(context).toEqual({ message: "Thanks. What are your support hours?", history, k: 5, usedTools: false });
    expect(request.kind).toBe("CONSISTENCY");
    expect(request.systemPrompts).toEqual([]);
    expect(request.scoring).toBe("NONE");
    expect(request.similarity).toBe("JUDGE");
    expect(request.cells).toEqual(
      ["k0", "k1", "k2", "k3", "k4"].map((key): LabCellRequest => ({
        key, promptIndex: null, message: "Thanks. What are your support hours?", history,
      }))
    );
  });

  it("sends no history when the box is unticked", () => {
    const { lab } = setup();
    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(runButton());
    const [request, context] = (lab.start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest, ConsistencyContext];
    expect(context.history).toEqual([]);
    expect(request.cells.every((c) => c.history.length === 0)).toBe(true);
  });

  it("warns when the chosen turn used tools, and records it on the run", () => {
    const { lab } = setup();
    // The latest turn answered directly.
    expect(screen.queryByText("ccToolsWarning")).not.toBeInTheDocument();
    // Turn 1 called two tools. It is the first exchange: no history to offer.
    choose(turnSelect(), 0);
    expect(screen.getByText("ccToolsWarning")).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    fireEvent.click(runButton());
    const [request, context] = (lab.start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest, ConsistencyContext];
    expect(context).toMatchObject({ message: FIXTURE_TURNS[0].content, history: [], usedTools: true });
    expect(request.cells[0].message).toBe(FIXTURE_TURNS[0].content);
  });

  it("samples a typed question with no history and no tools warning", () => {
    const { lab } = setup();
    fireEvent.click(screen.getByRole("radio", { name: "ccSourceCustom" }));
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    const textarea = screen.getByRole("textbox");
    expect(textarea).toHaveAttribute("maxlength", String(LAB_MAX_MESSAGE_CHARS));
    expect(textarea).toHaveAttribute("placeholder", "ccQuestionPlaceholder");
    // Nothing typed yet: nothing to run.
    expect(screen.getByText("estimateNone")).toBeInTheDocument();
    expect(runButton()).toBeDisabled();

    fireEvent.change(textarea, { target: { value: "  Do you ship to Nepal?  " } });
    expect(screen.getByText(/estimateCalls:\{"count":15\}/)).toBeInTheDocument();
    fireEvent.click(runButton());
    const [request, context] = (lab.start as ReturnType<typeof vi.fn>).mock.calls[0] as [LabRunRequest, ConsistencyContext];
    expect(context).toEqual({ message: "Do you ship to Nepal?", history: [], k: 5, usedTools: false });
    expect(request.cells).toHaveLength(5);
  });

  it("opens on a typed question when the playground has no server turn, and says so under the turn option", () => {
    const onDevice: LabTurn[] = [
      { role: "user", content: "hello" },
      { role: "assistant", content: "hi", onDevice: true },
    ];
    setup({ turns: onDevice });
    expect(screen.getByRole("radio", { name: "ccSourceCustom" })).toBeChecked();
    expect(screen.getByRole("textbox")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: "ccSourceTurn" }));
    expect(screen.getByText("ccNoTurns")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.getByText("estimateNone")).toBeInTheDocument();
    expect(runButton()).toBeDisabled();
  });

  it("notes a temperature of 0, and only then", () => {
    const { unmount } = setup();
    expect(screen.queryByText("ccZeroTempNote")).not.toBeInTheDocument();
    unmount();
    setup({ agent: { ...FIXTURE_AGENT, temperature: 0 } });
    expect(screen.getByText("ccZeroTempNote")).toBeInTheDocument();
  });

  it("refuses a playground message longer than a lab run accepts, with the reason in view", () => {
    const turns: LabTurn[] = [
      { role: "user", content: "x".repeat(LAB_MAX_MESSAGE_CHARS + 1) },
      { role: "assistant", content: "ok", ref: "r1" },
    ];
    const { lab } = setup({ turns });
    const reason = `koItemTooLong:${JSON.stringify({ n: 1, max: LAB_MAX_MESSAGE_CHARS })}`;
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", reason);
    expect(screen.getByRole("alert")).toHaveTextContent(reason);
    fireEvent.click(runButton());
    expect(lab.start).not.toHaveBeenCalled();
  });
});

describe("ConsistencyPanel gate", () => {
  it("is upgrade-locked, not dead, on a plan that may not run it", () => {
    const { lab } = setup({ capabilities: LOCKED });
    const button = runButton();
    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).toHaveAttribute("data-upgrade-locked");
    expect(button).not.toBeDisabled();
    fireEvent.click(button);
    expect(lab.start).not.toHaveBeenCalled();
    // The estimate is still stated, so the cost is known before upgrading.
    expect(screen.getByText(/estimateCalls:\{"count":15\}/)).toBeInTheDocument();
  });

  it("is disabled with the reason when the server has no lab routes", () => {
    const { lab } = setup({ capabilities: null });
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", "capabilitiesUnsupported");
    // Falls back to the default sample range and call cap.
    expect(samplesInput()).toHaveAttribute("max", "8");
    fireEvent.click(runButton());
    expect(lab.start).not.toHaveBeenCalled();
  });

  it("cannot start a second run while one is live, and shows its progress instead of a verdict", () => {
    const running = runOf({ status: "RUNNING", plannedCalls: 15, completedCalls: 4, finishedAtMs: null, cells: [cell("k0"), cell("k1")] });
    const { lab } = setup({}, labOf("polling", { run: running, context: CONTEXT }));
    expect(runButton()).toBeDisabled();
    fireEvent.click(runButton());
    expect(lab.start).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("statusRunning");
    expect(screen.getByRole("progressbar")).toBeInTheDocument();
    expect(screen.queryByText("ccStateInsufficient")).not.toBeInTheDocument();
    expect(screen.queryByText("ccGroupsTitle")).not.toBeInTheDocument();
  });

  it("cannot start a second run over one that is lost (it may still be going)", () => {
    const running = runOf({ status: "RUNNING", plannedCalls: 15, completedCalls: 4, finishedAtMs: null, cells: [cell("k0"), cell("k1")] });
    const { lab } = setup({}, labOf("lost", { run: running, context: CONTEXT }));
    expect(runButton()).toBeDisabled();
    fireEvent.click(runButton());
    expect(lab.start).not.toHaveBeenCalled();
    expect(screen.getByRole("status")).toHaveTextContent("statusLost");
    expect(screen.getByRole("button", { name: "cancel" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "resume" })).toBeInTheDocument();
  });

  it("shows nothing of a run before there is one", () => {
    setup();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(screen.queryByText("ccMethodNote")).not.toBeInTheDocument();
  });
});

describe("ConsistencyPanel results", () => {
  it("consistent: every sample in one group", () => {
    setup({}, labOf("done", { run: groupedRun([0, 0, 0, 0, 0]), context: CONTEXT }));
    const banner = screen.getByText("ccStateConsistent").closest("[data-consistency]");
    expect(banner).toHaveAttribute("data-consistency", "consistent");
    expect(screen.getByText('ccDetailConsistent:{"n":5}')).toBeInTheDocument();
    expect(screen.getByText(/ccEntropy:\{"bits":"0\.00","percent":0,"n":5\}/)).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "ccShareBarLabel" })).toBeInTheDocument();
    expect(screen.getByText('ccGroupCount:{"count":5,"total":5}')).toBeInTheDocument();
    expect(screen.getByText("ccMethodNote")).toBeInTheDocument();
    expect(screen.getByText("ccCaveat")).toBeInTheDocument();
    expect(screen.queryByText("partialResults")).not.toBeInTheDocument();
    expect(screen.queryByText(/ccUnknownNote/)).not.toBeInTheDocument();
    // What was asked is restated from the run's own context.
    expect(screen.getByText(/What are your support hours\?/, { selector: "section p" })).toBeInTheDocument();
  });

  it("mixed: a majority and the rest, with the entropy of the grouping", () => {
    setup({}, labOf("done", { run: groupedRun([0, 1, 0, 1, 0]), context: CONTEXT }));
    expect(screen.getByText("ccStateMixed").closest("[data-consistency]")).toHaveAttribute("data-consistency", "mixed");
    expect(screen.getByText('ccDetailMixed:{"majority":3,"n":5}')).toBeInTheDocument();
    // 0.971 bits of a possible log2(5) = 2.32: 42%.
    expect(screen.getByText(/ccEntropy:\{"bits":"0\.97","percent":42,"n":5\}/)).toBeInTheDocument();
    expect(screen.getByText("ccEntropyHelp")).toBeInTheDocument();
    expect(screen.getByText('ccGroupLabel:{"n":1}')).toBeInTheDocument();
    expect(screen.getByText('ccGroupCount:{"count":3,"total":5}')).toBeInTheDocument();
    expect(screen.getByText('ccGroupCount:{"count":2,"total":5}')).toBeInTheDocument();
  });

  it("unstable: no majority", () => {
    setup({}, labOf("done", { run: groupedRun([0, 1, 0, 1, 2]), context: CONTEXT }));
    expect(screen.getByText("ccStateUnstable").closest("[data-consistency]")).toHaveAttribute("data-consistency", "unstable");
    expect(screen.getByText('ccDetailUnstable:{"n":5}')).toBeInTheDocument();
  });

  it("shows each group's leader answer, and the rest on request", () => {
    setup({}, labOf("done", { run: groupedRun([0, 1, 0, 1, 0]), context: CONTEXT }));
    expect(screen.getByText("Answer from k0")).toBeInTheDocument();
    expect(screen.getByText("Answer from k1")).toBeInTheDocument();
    expect(screen.queryByText("Answer from k2")).not.toBeInTheDocument();
    const toggles = screen.getAllByRole("button", { name: "ccGroupShowAll" });
    expect(toggles).toHaveLength(2);
    fireEvent.click(toggles[0]);
    expect(screen.getByText("Answer from k2")).toBeInTheDocument();
    expect(screen.getByText("Answer from k4")).toBeInTheDocument();
    expect(screen.getByText('ccSampleLabel:{"n":5}')).toBeInTheDocument();
    expect(screen.queryByText("Answer from k3")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "ccGroupHide" }));
    expect(screen.queryByText("Answer from k2")).not.toBeInTheDocument();
  });

  it("missing verdicts that could change the answer: Inconclusive, no groups, no entropy, no 'disagree'", () => {
    // Cancelled after the five answers, before any comparison came back.
    const run = runOf({ status: "CANCELLED", cells: ["k0", "k1", "k2", "k3", "k4"].map((key) => cell(key)), similarity: null });
    const { container } = setup({}, labOf("done", { run, context: CONTEXT }));
    expect(screen.getByText("ccStateInsufficient").closest("[data-consistency]")).toHaveAttribute("data-consistency", "insufficient");
    expect(screen.getByText('ccDetailUnverified:{"count":10}')).toBeInTheDocument();
    expect(screen.queryByText(/ccDetailInsufficient/)).not.toBeInTheDocument();
    expect(screen.queryByText(/ccDetailMixed|ccDetailUnstable|ccStateMixed|ccStateUnstable/)).not.toBeInTheDocument();
    expect(screen.queryByText(/ccEntropy/)).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "ccShareBarLabel" })).not.toBeInTheDocument();
    expect(screen.queryByText("ccGroupsTitle")).not.toBeInTheDocument();
    expect(screen.queryByText(/ccGroupLabel/)).not.toBeInTheDocument();
    // The answers are still there to read, ungrouped...
    expect(screen.getByText("Answer from k0")).toBeInTheDocument();
    expect(screen.getByText("Answer from k4")).toBeInTheDocument();
    // ...every pair is drawn as "not compared", none as "different"...
    expect(container.querySelectorAll('[data-verdict="unknown"]')).toHaveLength(20);
    expect(container.querySelectorAll('[data-verdict="different"]')).toHaveLength(0);
    // ...and the run is marked as unfinished.
    expect(screen.getByText("partialResults")).toBeInTheDocument();
  });

  it("missing verdicts that cannot change the headline but do change the groups: the state stands, and the note says which reading is drawn", () => {
    // 0,1,2 agree; 3 and 4 each differ from them; 3-4 was never judged.
    // Read as different: 3 + 1 + 1. Read as same: 3 + 2.
    const run = groupedRun([0, 0, 0, 1, 2]);
    run.similarity.matrix[3][4] = null;
    run.similarity.matrix[4][3] = null;
    setup({}, labOf("done", { run, context: CONTEXT }));
    expect(screen.getByText("ccStateMixed")).toBeInTheDocument();
    expect(screen.getByText('ccDetailMixed:{"majority":3,"n":5}')).toBeInTheDocument();
    expect(screen.getByText('ccUnknownGroupsNote:{"count":1,"shown":3,"alt":2}')).toBeInTheDocument();
    // Not the sentence that claims both readings give the same result.
    expect(screen.queryByText(/ccUnknownNote/)).not.toBeInTheDocument();
  });

  it("missing verdicts that change neither the headline nor the groups: the plain note", () => {
    // 0,1,2 match the first answer and 3 differs; 1-2 was never judged.
    const run = groupedRun([0, 0, 0, 1]);
    run.similarity.matrix[1][2] = null;
    run.similarity.matrix[2][1] = null;
    setup({}, labOf("done", { run, context: { ...CONTEXT, k: 4 } }));
    expect(screen.getByText("ccStateMixed")).toBeInTheDocument();
    expect(screen.getByText('ccUnknownNote:{"count":1}')).toBeInTheDocument();
    expect(screen.queryByText(/ccUnknownGroupsNote/)).not.toBeInTheDocument();
  });

  it("never says 'all answers agree' over a pair the judge called different", () => {
    // Every answer matched the first one, but 1 and 2 were judged different.
    const run = groupedRun([0, 0, 0]);
    run.similarity.matrix[1][2] = 0;
    run.similarity.matrix[2][1] = 0;
    const { container } = setup({}, labOf("done", { run, context: { ...CONTEXT, k: 3 } }));
    expect(screen.getByText("ccStateInsufficient").closest("[data-consistency]")).toHaveAttribute("data-consistency", "insufficient");
    expect(screen.getByText('ccDetailContradictory:{"count":1}')).toBeInTheDocument();
    expect(screen.queryByText(/ccDetailConsistent|ccStateConsistent/)).not.toBeInTheDocument();
    expect(screen.queryByText(/ccEntropy/)).not.toBeInTheDocument();
    expect(screen.queryByText("ccGroupsTitle")).not.toBeInTheDocument();
    // The grid still draws the verdict that caused it.
    expect(container.querySelectorAll('[data-verdict="different"]')).toHaveLength(2);
  });

  it("never says 'all answers agree' when most comparisons did not run", () => {
    // Cancelled after the first row of comparisons: 0-1 .. 0-4 came back
    // "same", the other six pairs were never judged.
    const run = groupedRun([0, 0, 0, 0, 0]);
    run.status = "CANCELLED";
    for (let a = 1; a < 5; a++) {
      for (let b = 1; b < 5; b++) {
        if (a !== b) run.similarity.matrix[a][b] = null;
      }
    }
    setup({}, labOf("done", { run, context: CONTEXT }));
    expect(screen.getByText("ccStateInsufficient")).toBeInTheDocument();
    expect(screen.getByText('ccDetailUnverified:{"count":6}')).toBeInTheDocument();
    expect(screen.queryByText(/ccDetailConsistent|ccStateConsistent/)).not.toBeInTheDocument();
    expect(screen.getByText("partialResults")).toBeInTheDocument();
  });

  it("a contradiction inside one group of several is noted under the headline", () => {
    const run = groupedRun([0, 0, 0, 1]);
    run.similarity.matrix[1][2] = 0;
    run.similarity.matrix[2][1] = 0;
    setup({}, labOf("done", { run, context: { ...CONTEXT, k: 4 } }));
    expect(screen.getByText("ccStateMixed")).toBeInTheDocument();
    expect(screen.getByText('ccContradictionNote:{"count":1}')).toBeInTheDocument();
  });

  it("too few finished samples: Inconclusive with the counts, and the failures listed", () => {
    const run = runOf({
      status: "FAILED", errorMessage: "Every sample failed.",
      cells: [cell("k0", "ERROR"), cell("k1"), cell("k2", "ERROR")],
      similarity: { method: "JUDGE", status: "DONE", keys: ["k1"], matrix: [[1]] },
    });
    setup({}, labOf("done", { run, context: { ...CONTEXT, k: 3 } }));
    expect(screen.getByText("ccStateInsufficient")).toBeInTheDocument();
    expect(screen.getByText('ccDetailInsufficient:{"n":1,"k":3}')).toBeInTheDocument();
    expect(screen.getByText('ccSampleFailed:{"n":1,"reason":"The model call timed out."}')).toBeInTheDocument();
    expect(screen.getByText('ccSampleFailed:{"n":3,"reason":"The model call timed out."}')).toBeInTheDocument();
    expect(screen.getByText("Answer from k1")).toBeInTheDocument();
    expect(screen.getByText('ccSampleLabel:{"n":2}')).toBeInTheDocument();
    expect(screen.queryByRole("img", { name: "ccMatrixTitle" })).not.toBeInTheDocument();
    expect(screen.getByText("partialResults")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent('statusFailed:{"reason":"Every sample failed."}');
  });

  it("draws the pairwise grid with a title per pair and a legend for the three verdicts", () => {
    const run = groupedRun([0, 1, 0]);
    run.similarity.matrix[1][2] = null;
    run.similarity.matrix[2][1] = null;
    const { container } = setup({}, labOf("done", { run, context: CONTEXT }));
    const grid = screen.getByRole("img", { name: "ccMatrixTitle" });
    const titles = Array.from(grid.querySelectorAll("title")).map((node) => node.textContent);
    expect(titles).toHaveLength(6);
    expect(titles).toContain('ccMatrixCell:{"a":1,"b":3,"verdict":"ccMatrixSame"}');
    expect(titles).toContain('ccMatrixCell:{"a":1,"b":2,"verdict":"ccMatrixDifferent"}');
    expect(titles).toContain('ccMatrixCell:{"a":2,"b":3,"verdict":"ccMatrixUnknown"}');
    // Each verdict has its own glyph, not only its own tint.
    const glyphOf = (verdict: string) => container.querySelector(`[data-verdict="${verdict}"] text`)?.textContent;
    expect(new Set([glyphOf("same"), glyphOf("different"), glyphOf("unknown")]).size).toBe(3);
    // The legend under the grid names all three.
    expect(screen.getByText("ccMatrixSame")).toBeInTheDocument();
    expect(screen.getByText("ccMatrixDifferent")).toBeInTheDocument();
    expect(screen.getByText("ccMatrixUnknown")).toBeInTheDocument();
  });

  it("numbers the share bar's segments so groups are not told apart by colour alone", () => {
    setup({}, labOf("done", { run: groupedRun([0, 1, 0, 1, 2]), context: CONTEXT }));
    const bar = screen.getByRole("img", { name: "ccShareBarLabel" });
    expect(Array.from(bar.querySelectorAll("text")).map((node) => node.textContent)).toEqual(["1", "2", "3"]);
    expect(Array.from(bar.querySelectorAll("title")).map((node) => node.textContent)).toEqual([
      'ccGroupLabel:{"n":1}: ccGroupCount:{"count":2,"total":5}',
      'ccGroupLabel:{"n":2}: ccGroupCount:{"count":2,"total":5}',
      'ccGroupLabel:{"n":3}: ccGroupCount:{"count":1,"total":5}',
    ]);
  });

  it("repeats the tools warning on a run whose turn used tools", () => {
    setup({}, labOf("done", { run: groupedRun([0, 0, 0]), context: { ...CONTEXT, k: 3, usedTools: true } }));
    // The form is on the latest turn (no tools), so this one is the results'.
    expect(screen.getAllByText("ccToolsWarning")).toHaveLength(1);
  });

  it("keeps the last snapshot readable after the run has expired, marked partial", () => {
    const snapshot = groupedRun([0, 0, 1], { status: "RUNNING", finishedAtMs: null });
    setup({}, labOf("expired", { run: snapshot, context: CONTEXT }));
    expect(screen.getByRole("status")).toHaveTextContent('statusExpired:{"minutes":60}');
    expect(screen.getByText("ccStateMixed")).toBeInTheDocument();
    expect(screen.getByText("partialResults")).toBeInTheDocument();
  });

  it("clears through the hook's reset", () => {
    const { lab } = setup({}, labOf("done", { run: groupedRun([0, 0, 0]), context: CONTEXT }));
    fireEvent.click(screen.getByRole("button", { name: "runAgain" }));
    expect(lab.reset).toHaveBeenCalledTimes(1);
  });

  it("never calls disagreement a hallucination, in the copy or in the code", () => {
    const copy = Object.entries((en as unknown as Record<string, Record<string, string>>).agentLab)
      .filter(([key]) => key.startsWith("cc"))
      .map(([, value]) => value);
    expect(copy.length).toBeGreaterThan(30);
    const sources = ["ConsistencyPanel.tsx", "ConsistencyResults.tsx", "ClusterShareBar.tsx", "EquivalenceGrid.tsx"].map((file) =>
      fs.readFileSync(path.resolve(process.cwd(), "app/components/agents/lab", file), "utf8")
    );
    for (const text of [...copy, ...sources]) expect(text.toLowerCase()).not.toContain("hallucinat");
  });
});
