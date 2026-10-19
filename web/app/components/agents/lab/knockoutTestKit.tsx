// Shared fixtures for the KnockoutPanel test files (form, limits and datasets, results). Each test
// file keeps its own vi.hoisted state and vi.mock calls (they are hoisted per file); this module
// holds the plain fixtures and the helpers that drive the rendered panel.

import { vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import KnockoutPanel from "./KnockoutPanel";
import type { LabCapabilities, LabCellResult, LabRun, LabRunStatus } from "./labApi";
import { FIXTURE_AGENT } from "./labFixtures";
import type { KnockoutContext } from "./knockoutPlan";
import { splitPromptSections } from "./promptSections";
import type { LabAgent } from "./types";
import type { LabRunPhase, UseLabRun } from "./useLabRun";

/** The mocked-module state each test file creates with vi.hoisted. */
export interface KnockoutMocks {
  lab: unknown;
  useLabRunArgs: unknown[][];
  listEvalDatasets: ReturnType<typeof vi.fn>;
  getEvalDataset: ReturnType<typeof vi.fn>;
}

export const ALLOWED: LabCapabilities = {
  allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60,
};
export const LOCKED: LabCapabilities = { ...ALLOWED, allowed: false, reason: "PLAN" };

// FIXTURE_AGENT's prompt splits into three sections: Role, Tone, Escalation.
export const SECTIONS = splitPromptSections(FIXTURE_AGENT.systemPrompt as string).sections;

export function labOf(phase: LabRunPhase = "idle", extra: Partial<UseLabRun<KnockoutContext>> = {}): UseLabRun<KnockoutContext> {
  return {
    run: null, context: null, phase, error: null, cancelling: false,
    start: vi.fn(async () => {}),
    cancel: vi.fn(async () => {}),
    resume: vi.fn(),
    reset: vi.fn(),
    ...extra,
  };
}

export function cellOf(key: string, score: number | null, extra: Partial<LabCellResult> = {}): LabCellResult {
  return {
    key, status: "DONE", answer: `answer for ${key}`, answerTruncated: false,
    score, scoreStatus: score === null ? "UNAVAILABLE" : "SCORED",
    scoreFeedback: score === null ? null : `note for ${key}`, errorMessage: null, ms: 900,
    ...extra,
  };
}

export function runOf(status: LabRunStatus, cells: LabCellResult[]): LabRun {
  return {
    runId: "run-1", agentId: FIXTURE_AGENT.id, kind: "KNOCKOUT", status,
    plannedCalls: 24, completedCalls: cells.length * 2, cancelRequested: false,
    errorMessage: null, createdAtMs: 1, updatedAtMs: 2, finishedAtMs: status === "RUNNING" ? null : 2,
    cells, similarity: null,
  };
}

export const RUN_CONTEXT: KnockoutContext = {
  sections: SECTIONS,
  testedSectionIds: ["s1", "s2", "s3"],
  items: [
    { message: "Where is my order?", expected: null },
    { message: "Can I get a refund?", expected: null },
  ],
  repeats: 3,
  prompt: FIXTURE_AGENT.systemPrompt as string,
};

// Baselines never vary, so the variance is floored at the judge's resolution:
// 2 questions x 3 repeats, 3 sections -> a noise floor of 0.09.
//   s1 removed: 0.2 / 0.1  -> score drops by 0.55 -> load-bearing
//   s2 removed: 0.9 / 0.9  -> score rises by 0.20 -> harmful
//   s3 removed: 0.8 / 0.6  -> no change          -> no measurable effect
export const FULL_CELLS: LabCellResult[] = [
  cellOf("b0:i0", 0.8), cellOf("b1:i0", 0.8), cellOf("b2:i0", 0.8),
  cellOf("s1:i0", 0.2), cellOf("s2:i0", 0.9), cellOf("s3:i0", 0.8),
  cellOf("b0:i1", 0.6), cellOf("b1:i1", 0.6), cellOf("b2:i1", 0.6),
  cellOf("s1:i1", 0.1), cellOf("s2:i1", 0.9), cellOf("s3:i1", 0.6),
];

/** Let the dataset reads (mocked promises) settle inside act. Two rounds:
 * the list read, then the chosen dataset's cases read it triggers. */
export async function settle() {
  for (let round = 0; round < 2; round++) {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  }
}

export async function renderPanel(props: { agent?: LabAgent; capabilities?: LabCapabilities | null } = {}) {
  const view = render(
    <KnockoutPanel agent={props.agent ?? FIXTURE_AGENT} capabilities={props.capabilities === undefined ? ALLOWED : props.capabilities} />
  );
  await settle();
  return view;
}

export function useProbes(text: string) {
  fireEvent.click(screen.getByRole("radio", { name: "koSourceProbes" }));
  fireEvent.change(screen.getByLabelText("koProbesLabel"), { target: { value: text } });
}

export const runButton = () => screen.getByRole("button", { name: "koRun" });

/** Fresh lab state and the console-preview mock's answer for every quality route. */
export function resetKnockoutMocks(hoisted: KnockoutMocks) {
hoisted.lab = labOf();
hoisted.useLabRunArgs.length = 0;
hoisted.listEvalDatasets.mockReset();
hoisted.getEvalDataset.mockReset();
// The console-preview mock's answer for every quality route.
hoisted.listEvalDatasets.mockResolvedValue({});
hoisted.getEvalDataset.mockResolvedValue({});
}
