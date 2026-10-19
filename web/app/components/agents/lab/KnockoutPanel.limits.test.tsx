// Prompt Knockout panel, part 2: every reason a run cannot start is shown before start, and the
// eval-dataset source degrades to probe questions (FREE plan 403, the preview's `{}`) without an
// unhandled rejection. Shared fixtures: knockoutTestKit.tsx.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { chooseLabel } from "./selectTestUtils";

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
  renderPanel,
  resetKnockoutMocks,
  runButton,
  settle,
  useProbes,
} from "./knockoutTestKit";
import { LAB_MAX_MESSAGE_CHARS, LAB_MAX_PROMPT_CHARS } from "./labApi";
import type { KnockoutContext } from "./knockoutPlan";
import { FIXTURE_AGENT } from "./labFixtures";
import type { UseLabRun } from "./useLabRun";

const currentLab = () => hoisted.lab as UseLabRun<KnockoutContext>;

beforeEach(() => resetKnockoutMocks(hoisted));

describe("KnockoutPanel limits shown before start", () => {
  it("koPromptTooLong: a prompt longer than a lab run can send", async () => {
    const prompt = `# Role\n${"x".repeat(LAB_MAX_PROMPT_CHARS)}`;
    await renderPanel({ agent: { ...FIXTURE_AGENT, systemPrompt: prompt } });
    useProbes("q1");
    const reason = `koPromptTooLong:{"max":${LAB_MAX_PROMPT_CHARS}}`;
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", reason);
    expect(screen.getByText(reason)).toBeInTheDocument();
  });

  it("koSectionsNone: no section ticked", async () => {
    await renderPanel();
    useProbes("q1");
    screen.getAllByRole("checkbox").forEach((box) => fireEvent.click(box));
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", "koSectionsNone");
    expect(screen.getByText("koSectionsNone")).toBeInTheDocument();
  });

  it("koItemsNone: no question yet", async () => {
    await renderPanel();
    fireEvent.click(screen.getByRole("radio", { name: "koSourceProbes" }));
    expect(screen.getByText("estimateNone")).toBeInTheDocument();
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", "koItemsNone");
    expect(screen.getByText("koItemsNone")).toBeInTheDocument();
  });

  it("koTooFewToLabel: a plan that could never label a section is not allowed to spend", async () => {
    await renderPanel();
    const reason = 'koTooFewToLabel:{"items":2,"df":4}';
    // 1 question x 3 repeats: 2 degrees of freedom, and a single question.
    useProbes("q1");
    expect(screen.getByText(/estimateCalls:\{"count":12\}/)).toBeInTheDocument();
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", reason);
    expect(screen.getByText(reason)).toBeInTheDocument();
    fireEvent.click(runButton());
    expect(currentLab().start).not.toHaveBeenCalled();
    // 1 question x 5 repeats: enough repeats, still one question.
    fireEvent.change(screen.getByLabelText("koRepeatsLabel"), { target: { value: "5" } });
    expect(runButton()).toBeDisabled();
    // 2 questions x 2 repeats: 2 degrees of freedom.
    useProbes("q1\nq2");
    fireEvent.change(screen.getByLabelText("koRepeatsLabel"), { target: { value: "2" } });
    expect(runButton()).toBeDisabled();
    expect(screen.getByText(reason)).toBeInTheDocument();
    // 2 questions x 3 repeats is the smallest plan that can label.
    fireEvent.change(screen.getByLabelText("koRepeatsLabel"), { target: { value: "3" } });
    expect(runButton()).toBeEnabled();
    expect(screen.queryByText(reason)).not.toBeInTheDocument();
    // So is 4 questions x 2 repeats.
    useProbes("q1\nq2\nq3\nq4");
    fireEvent.change(screen.getByLabelText("koRepeatsLabel"), { target: { value: "2" } });
    expect(runButton()).toBeEnabled();
  });

  it("koItemTooLong: names the question that is over the message limit", async () => {
    await renderPanel();
    useProbes(`short question\n${"y".repeat(LAB_MAX_MESSAGE_CHARS + 1)}`);
    const reason = `koItemTooLong:{"n":2,"max":${LAB_MAX_MESSAGE_CHARS}}`;
    expect(runButton()).toBeDisabled();
    expect(runButton()).toHaveAttribute("title", reason);
    expect(screen.getByText(reason)).toBeInTheDocument();
    fireEvent.click(runButton());
    expect(currentLab().start).not.toHaveBeenCalled();

    // Exactly at the limit is fine.
    useProbes(`short question\n${"y".repeat(LAB_MAX_MESSAGE_CHARS)}`);
    expect(runButton()).toBeEnabled();
    expect(screen.queryByText(/koItemTooLong/)).not.toBeInTheDocument();
  });
});

describe("KnockoutPanel eval datasets", () => {
  it("shows koDatasetNone when the list read is rejected (FREE plan 403)", async () => {
    hoisted.listEvalDatasets.mockRejectedValue(Object.assign(new Error("Forbidden"), { status: 403 }));
    await renderPanel();
    expect(hoisted.listEvalDatasets).toHaveBeenCalledWith(FIXTURE_AGENT.id);
    expect(screen.getByText("koDatasetNone")).toBeInTheDocument();
    expect(screen.queryByText("koDatasetLoading")).not.toBeInTheDocument();
    expect(hoisted.getEvalDataset).not.toHaveBeenCalled();
    expect(runButton()).toBeDisabled();
    expect(screen.getByText("koItemsNone")).toBeInTheDocument();
  });

  it("shows koDatasetNone when the list resolves to something that is not a list (the preview's {})", async () => {
    hoisted.listEvalDatasets.mockResolvedValue({});
    await renderPanel();
    expect(screen.getByText("koDatasetNone")).toBeInTheDocument();
    expect(hoisted.getEvalDataset).not.toHaveBeenCalled();
  });

  it("shows koDatasetNone for an empty list", async () => {
    hoisted.listEvalDatasets.mockResolvedValue([]);
    await renderPanel();
    expect(screen.getByText("koDatasetNone")).toBeInTheDocument();
  });

  it("shows the loading line until the list answers", async () => {
    let resolve: (value: unknown) => void = () => {};
    hoisted.listEvalDatasets.mockReturnValue(new Promise((r) => (resolve = r)));
    await renderPanel();
    expect(screen.getByText("koDatasetLoading")).toBeInTheDocument();
    resolve([]);
    await settle();
    expect(screen.getByText("koDatasetNone")).toBeInTheDocument();
  });

  it("shows koDatasetNone when the chosen dataset has no cases array, or its read is rejected", async () => {
    hoisted.listEvalDatasets.mockResolvedValue([{ id: "d1", name: "Refunds", caseCount: 4 }]);
    hoisted.getEvalDataset.mockResolvedValue({});
    const first = await renderPanel();
    expect(hoisted.getEvalDataset).toHaveBeenCalledWith(FIXTURE_AGENT.id, "d1");
    expect(screen.getByText("koDatasetNone")).toBeInTheDocument();
    first.unmount();

    hoisted.getEvalDataset.mockRejectedValue(Object.assign(new Error("Forbidden"), { status: 403 }));
    await renderPanel();
    expect(screen.getByText("koDatasetNone")).toBeInTheDocument();
  });

  it("uses the first cases of the chosen dataset, with their expected answers", async () => {
    const cases = Array.from({ length: 7 }, (_, i) => ({
      id: `c${i}`,
      userMessage: `case question ${i + 1}`,
      expectedOutput: i === 0 ? "the reference answer" : i === 1 ? "   " : null,
    }));
    hoisted.listEvalDatasets.mockResolvedValue([
      { id: "d1", name: "Refunds", caseCount: 7 },
      { id: "d2", name: "Deliveries", caseCount: 2 },
    ]);
    hoisted.getEvalDataset.mockImplementation(async (_agentId: string, datasetId: string) =>
      datasetId === "d1"
        ? { dataset: { id: "d1" }, cases }
        : { dataset: { id: "d2" }, cases: [{ id: "x", userMessage: "only case", expectedOutput: "ok" }, { id: "y", userMessage: "  " }] }
    );
    await renderPanel();

    expect(screen.getByLabelText("koDatasetLabel")).toHaveTextContent("Refunds");
    expect(screen.getByLabelText("koDatasetCount")).toHaveValue(5);
    expect(screen.getByText(/koDatasetUsing:\{"used":5,"total":7\}/)).toBeInTheDocument();
    expect(screen.getByText(/koDatasetNote/)).toBeInTheDocument();
    // 5 cases x (3 + 3) = 30 cells.
    expect(screen.getByText(/estimateCalls:\{"count":60\}/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("koDatasetCount"), { target: { value: "2" } });
    expect(screen.getByText(/koDatasetUsing:\{"used":2,"total":7\}/)).toBeInTheDocument();
    fireEvent.click(runButton());
    const start = currentLab().start as ReturnType<typeof vi.fn>;
    const [request, context] = start.mock.calls[0];
    expect(context.items).toEqual([
      { message: "case question 1", expected: "the reference answer" },
      { message: "case question 2", expected: null },
    ]);
    expect(request.cells).toHaveLength(12);
    expect(request.cells[0]).toMatchObject({ key: "b0:i0", message: "case question 1", expected: "the reference answer" });
    expect(request.cells[6]).toMatchObject({ key: "b0:i1", message: "case question 2", expected: null });

    // The count cannot exceed 10 or the dataset's size.
    fireEvent.change(screen.getByLabelText("koDatasetCount"), { target: { value: "50" } });
    expect(screen.getByLabelText("koDatasetCount")).toHaveValue(7);

    // Switching dataset reloads its cases and resets the count; a blank case is not usable.
    chooseLabel(screen.getByLabelText("koDatasetLabel"), "Deliveries");
    await settle();
    expect(hoisted.getEvalDataset).toHaveBeenLastCalledWith(FIXTURE_AGENT.id, "d2");
    expect(screen.getByText(/koDatasetUsing:\{"used":1,"total":1\}/)).toBeInTheDocument();
  });
});