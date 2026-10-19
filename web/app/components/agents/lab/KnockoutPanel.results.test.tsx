// Prompt Knockout panel, part 3: results render from the run's own stored context, partial and
// in-flight runs included, with copy-without and the per-question table. Shared fixtures:
// knockoutTestKit.tsx.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";

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
  FULL_CELLS,
  RUN_CONTEXT,
  SECTIONS,
  cellOf,
  labOf,
  renderPanel,
  resetKnockoutMocks,
  runOf,
  settle,
} from "./knockoutTestKit";
import type { KnockoutContext } from "./knockoutPlan";
import { promptWithout } from "./promptSections";
import { FIXTURE_AGENT } from "./labFixtures";

beforeEach(() => resetKnockoutMocks(hoisted));

describe("KnockoutPanel results", () => {
  it("renders nothing under the form before a run has any scored baseline", async () => {
    hoisted.lab = labOf("polling", {
      run: runOf("RUNNING", [cellOf("b0:i0", null, { status: "PENDING", answer: null, scoreStatus: "PENDING" })]),
      context: RUN_CONTEXT,
    });
    await renderPanel();
    expect(screen.queryByText("koResultsTitle")).not.toBeInTheDocument();
  });

  it("labels each section from the finished run, stating the items, repeats and noise floor", async () => {
    hoisted.lab = labOf("done", { run: runOf("SUCCEEDED", FULL_CELLS), context: RUN_CONTEXT });
    await renderPanel();
    expect(screen.getByText("koResultsTitle")).toBeInTheDocument();
    expect(screen.queryByText("partialResults")).not.toBeInTheDocument();
    expect(screen.getByText(/koMeasuredOn:\{"items":2,"repeats":3\}/)).toBeInTheDocument();
    expect(screen.getByText(/koBaselineScore:\{"score":"0\.70"\}/)).toBeInTheDocument();
    expect(screen.getByText(/koNoiseFloor:\{"value":"0\.09"\}/)).toBeInTheDocument();
    expect(screen.getByText("koNoiseFloorHelp")).toBeInTheDocument();
    expect(screen.queryByText("koLiveNote")).not.toBeInTheDocument();
    expect(screen.getByText("koCaveat")).toBeInTheDocument();
    expect(screen.getByText("koDeltaHelp")).toBeInTheDocument();

    const rows = screen.getAllByRole("listitem").filter((row) => within(row).queryByRole("img"));
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByRole("heading", { name: "Role" })).toBeInTheDocument();
    expect(within(rows[0]).getByText("koLabelLoadBearing")).toBeInTheDocument();
    expect(within(rows[0]).getByText(/koDetailLoadBearing:\{"delta":"0\.55"\}/)).toBeInTheDocument();
    expect(within(rows[0]).getByText(/koItemsAgree:\{"agree":2,"total":2\}/)).toBeInTheDocument();
    expect(within(rows[0]).getByRole("img")).toHaveAccessibleName('koChartLabel: +0.55, koNoiseFloor:{"value":"0.09"}');

    expect(within(rows[1]).getByText("koLabelHarmful")).toBeInTheDocument();
    expect(within(rows[1]).getByText(/koDetailHarmful:\{"delta":"0\.20"\}/)).toBeInTheDocument();
    expect(within(rows[1]).getByRole("img")).toHaveAccessibleName('koChartLabel: −0.20, koNoiseFloor:{"value":"0.09"}');

    expect(within(rows[2]).getByText("koLabelNoEffect")).toBeInTheDocument();
    expect(within(rows[2]).getByText(/koDetailNoEffect:\{"delta":"0\.00"\}/)).toBeInTheDocument();
    expect(within(rows[2]).getByRole("img")).toHaveAccessibleName('koChartLabel: 0.00, koNoiseFloor:{"value":"0.09"}');
  });

  it("renders a cancelled run's partial results, and labels nothing from the one question that finished", async () => {
    // Only the first question finished: one item, and nothing for s3.
    const cells = FULL_CELLS.filter((cell) => cell.key.endsWith(":i0") && cell.key !== "s3:i0");
    hoisted.lab = labOf("done", { run: runOf("CANCELLED", cells), context: RUN_CONTEXT });
    await renderPanel();
    expect(screen.getByText("partialResults")).toBeInTheDocument();
    expect(screen.getByText(/koMeasuredOnOne:\{"repeats":3\}/)).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("statusCancelled");
    // One question is 2 degrees of freedom: no noise floor, so no label.
    expect(screen.getByText("koNoBaseline")).toBeInTheDocument();
    expect(screen.queryByText(/koLabelLoadBearing|koLabelHarmful|koLabelNoEffect/)).not.toBeInTheDocument();
    const rows = screen.getAllByRole("listitem").filter((row) => within(row).queryByRole("img"));
    // s1 WAS measured (0.8 -> 0.2); what is missing is the floor, and the row says that.
    expect(within(rows[0]).getByText("koLabelNotMeasured")).toBeInTheDocument();
    expect(within(rows[0]).getByText(/koDetailNoFloor:\{"change":"−0\.60"\}/)).toBeInTheDocument();
    expect(within(rows[0]).queryByText("koDetailNotMeasured")).not.toBeInTheDocument();
    expect(within(rows[0]).getByText(/koItemsAgree:\{"agree":1,"total":1\}/)).toBeInTheDocument();
    expect(within(rows[0]).getByRole("img")).toHaveAccessibleName("koChartLabel: +0.60");
    // s3 has no score at all: that is the only row that says so.
    expect(within(rows[2]).getByText("koLabelNotMeasured")).toBeInTheDocument();
    expect(within(rows[2]).getByText("koDetailNotMeasured")).toBeInTheDocument();
    expect(within(rows[2]).queryByText(/koItemsAgree/)).not.toBeInTheDocument();
    expect(within(rows[2]).getByRole("img")).toHaveAccessibleName('koChartLabel: koLabelNotMeasured');
  });

  it("labels nothing when the baseline was only scored once per question", async () => {
    const cells = FULL_CELLS.filter((cell) => !cell.key.startsWith("b1:") && !cell.key.startsWith("b2:"));
    hoisted.lab = labOf("done", { run: runOf("FAILED", cells), context: RUN_CONTEXT });
    await renderPanel();
    expect(screen.getByText("koNoBaseline")).toBeInTheDocument();
    expect(screen.queryByText(/koNoiseFloor:/)).not.toBeInTheDocument();
    expect(screen.getAllByText("koLabelNotMeasured")).toHaveLength(3);
    expect(screen.queryByText("koLabelLoadBearing")).not.toBeInTheDocument();
    expect(screen.getByText("partialResults")).toBeInTheDocument();
  });

  it("shows the changes so far while the run is still going, with no label and no noise floor", async () => {
    // Every cell is already in, and they would label all three sections -
    // but the run has not said it is finished.
    hoisted.lab = labOf("polling", { run: runOf("RUNNING", FULL_CELLS), context: RUN_CONTEXT });
    await renderPanel();
    expect(screen.getByText("koResultsTitle")).toBeInTheDocument();
    expect(screen.queryByText("partialResults")).not.toBeInTheDocument();
    expect(screen.getByText("koLiveNote")).toBeInTheDocument();
    expect(screen.queryByText(/koNoiseFloor:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/koLabelLoadBearing|koLabelHarmful|koLabelNoEffect|koLabelNotMeasured/)).not.toBeInTheDocument();
    expect(screen.getAllByText("koLabelPending")).toHaveLength(3);
    const rows = screen.getAllByRole("listitem").filter((row) => within(row).queryByRole("img"));
    expect(within(rows[0]).getByText(/koDetailPending:\{"change":"−0\.55"\}/)).toBeInTheDocument();
    expect(within(rows[1]).getByText(/koDetailPending:\{"change":"\+0\.20"\}/)).toBeInTheDocument();
    // The bar is drawn, without a floor band to compare it with.
    expect(within(rows[0]).getByRole("img")).toHaveAccessibleName("koChartLabel: +0.55");
  });

  it("holds the labels while the run is lost too, and shows them once it is known to have finished", async () => {
    // The first question's baselines and two of its three variants are in.
    hoisted.lab = labOf("lost", { run: runOf("RUNNING", FULL_CELLS.slice(0, 5)), context: RUN_CONTEXT });
    const first = await renderPanel();
    expect(screen.getAllByText("koLabelPending")).toHaveLength(3);
    // Section 3 has no score yet.
    expect(screen.getByText("koDetailPendingNone")).toBeInTheDocument();
    first.unmount();

    hoisted.lab = labOf("done", { run: runOf("SUCCEEDED", FULL_CELLS), context: RUN_CONTEXT });
    await renderPanel();
    expect(screen.queryByText("koLabelPending")).not.toBeInTheDocument();
    expect(screen.getByText("koLabelLoadBearing")).toBeInTheDocument();
  });

  it("reads results from the run's stored context, not from the form", async () => {
    hoisted.lab = labOf("done", { run: runOf("SUCCEEDED", FULL_CELLS), context: RUN_CONTEXT });
    // The agent's prompt has changed since the run: the form shows one section.
    await renderPanel({ agent: { ...FIXTURE_AGENT, systemPrompt: "Just be helpful and answer every question you are asked in full." } });
    expect(screen.getAllByRole("checkbox")).toHaveLength(1);
    expect(screen.getByRole("heading", { name: "Role" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Escalation" })).toBeInTheDocument();
  });

  it("ignores a stored context it cannot read", async () => {
    hoisted.lab = labOf("done", { run: runOf("SUCCEEDED", FULL_CELLS), context: { stale: true } as unknown as KnockoutContext });
    await renderPanel();
    expect(screen.queryByText("koResultsTitle")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("statusSucceeded");
  });

  it("copies the prompt without a section", async () => {
    const writeText = vi.fn(async () => {});
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    hoisted.lab = labOf("done", { run: runOf("SUCCEEDED", FULL_CELLS), context: RUN_CONTEXT });
    await renderPanel();
    const buttons = screen.getAllByRole("button", { name: "koCopyWithout" });
    expect(buttons).toHaveLength(3);
    fireEvent.click(buttons[1]);
    await settle();
    expect(writeText).toHaveBeenCalledWith(promptWithout(SECTIONS, ["s2"]));
    expect(screen.getByRole("button", { name: "copied" })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "koCopyWithout" })).toHaveLength(2);
  });

  it("does not claim a copy the browser refused", async () => {
    const writeText = vi.fn(async () => {
      throw new Error("denied");
    });
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    hoisted.lab = labOf("done", { run: runOf("SUCCEEDED", FULL_CELLS), context: RUN_CONTEXT });
    await renderPanel();
    fireEvent.click(screen.getAllByRole("button", { name: "koCopyWithout" })[0]);
    await settle();
    expect(writeText).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "copied" })).not.toBeInTheDocument();
  });

  it("opens the per-question table, with the answers, judge notes and failed calls behind it", async () => {
    const cells = FULL_CELLS.map((cell) =>
      cell.key === "s1:i1"
        ? cellOf("s1:i1", null, { status: "ERROR", answer: null, scoreStatus: "NONE", errorMessage: "The model call timed out." })
        : cell
    );
    hoisted.lab = labOf("done", { run: runOf("SUCCEEDED", cells), context: RUN_CONTEXT });
    await renderPanel();
    const rows = screen.getAllByRole("listitem").filter((row) => within(row).queryByRole("img"));
    const row = within(rows[0]);
    expect(row.queryByRole("table")).not.toBeInTheDocument();
    const toggle = row.getByRole("button", { name: "koPerItemTitle" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    const table = within(row.getByRole("table"));
    for (const header of ["koColQuestion", "koColBaseline", "koColWithout", "koColChange"]) {
      expect(table.getByRole("columnheader", { name: header })).toBeInTheDocument();
    }
    const first = within(table.getByRole("rowheader", { name: /Where is my order\?/ }).closest("tr") as HTMLElement);
    expect(first.getByText("0.80")).toBeInTheDocument();
    expect(first.getByText("0.20")).toBeInTheDocument();
    // Without (0.20) minus baseline (0.80): the score went DOWN, and the sign says so.
    expect(first.getByText("−0.60")).toBeInTheDocument();
    expect(first.queryByText("+0.60")).not.toBeInTheDocument();
    // The failed variant call has no score and no change for that question.
    const second = within(table.getByRole("rowheader", { name: /Can I get a refund\?/ }).closest("tr") as HTMLElement);
    expect(second.getByText("0.60")).toBeInTheDocument();
    expect(second.getByText("koNoScore")).toBeInTheDocument();
    expect(second.getByText("–")).toBeInTheDocument();

    fireEvent.click(first.getByRole("button", { name: "koShowAnswers" }));
    expect(table.getByText("answer for b0:i0")).toBeInTheDocument();
    expect(table.getByText("answer for b2:i0")).toBeInTheDocument();
    expect(table.getByText("answer for s1:i0")).toBeInTheDocument();
    expect(table.getByText("koJudgeNote: note for s1:i0")).toBeInTheDocument();
    expect(table.queryByText("answer for b0:i1")).not.toBeInTheDocument();
    expect(first.getByRole("button", { name: "koHideAnswers" })).toBeInTheDocument();

    fireEvent.click(second.getByRole("button", { name: "koShowAnswers" }));
    expect(table.getByText('koCellFailed:{"reason":"The model call timed out."}')).toBeInTheDocument();
    expect(table.getByText("answer for b0:i1")).toBeInTheDocument();
  });
});
