// The replay-mode controls inside the real eval datasets section: create a dataset that follows the workspace default,
// pin the full pipeline with sandbox auto-approval or quick text only, change the mode of an existing dataset, choose
// the mode of one run, see the mode of every run and the tools the sandbox simulated, and what a refused
// auto-approval looks like. The API object is mocked at the fetch/client boundary (app/lib/api); every component is
// the real one.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";

const api = vi.hoisted(() => ({
  listEvalDatasets: vi.fn(),
  createEvalDataset: vi.fn(),
  getEvalDataset: vi.fn(),
  updateEvalDataset: vi.fn(),
  deleteEvalDataset: vi.fn(),
  addEvalDatasetCase: vi.fn(),
  updateEvalDatasetCase: vi.fn(),
  deleteEvalDatasetCase: vi.fn(),
  triggerEvalRun: vi.fn(),
  listEvalRuns: vi.fn(),
  getEvalRun: vi.fn(),
  cancelEvalRun: vi.fn(),
  getTrajectoryEvalConfig: vi.fn(),
  getTrajectoryEvalCase: vi.fn(),
  getTrajectoryEvalRun: vi.fn(),
}));
vi.mock("../../lib/api", () => ({ api }));
vi.mock("../../lib/i18n", async () => {
  const en = (await import("../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import EvalDatasetsSection from "./EvalDatasetsSection";
import type { EvalDatasetSummary, EvalRunSummary } from "./evalDatasetsApi";
import { pickOption } from "../../components/ui/test-select";

const AGENT = "section-agent-1";

const base: EvalDatasetSummary = {
  id: "d1",
  agentId: AGENT,
  name: "Billing FAQ",
  description: "Refund questions",
  passThreshold: 0.7,
  toolExecutionEnabled: false,
  autoApproveTools: false,
  caseCount: 2,
  createdBy: null,
  createdAt: "2026-10-01T00:00:00Z",
  updatedAt: "2026-10-01T00:00:00Z",
};

const run = (patch: Partial<EvalRunSummary> = {}): EvalRunSummary => ({
  id: "r1",
  datasetId: "d1",
  agentId: AGENT,
  status: "SUCCEEDED",
  verdict: "PASSED",
  passThreshold: 0.7,
  overallScore: 0.9,
  caseCount: 2,
  scoredCaseCount: 2,
  failedAssertionCount: 0,
  erroredCaseCount: 0,
  judgeModel: null,
  errorMessage: null,
  requestedBy: null,
  cancelRequested: false,
  createdAt: "2026-10-02T00:00:00Z",
  startedAt: null,
  finishedAt: null,
  baselineRunId: null,
  baselineScore: null,
  scoreDelta: null,
  ...patch,
});

async function confirmAutoApprove() {
  fireEvent.click(await screen.findByRole("button", { name: "Turn on auto-approval" }));
  await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
}

async function expandFirst() {
  fireEvent.click(await screen.findByRole("button", { name: "Billing FAQ" }));
  return screen.findByRole("radiogroup", { name: "How this dataset is replayed" });
}

describe("EvalDatasetsSection replay mode", () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.listEvalDatasets.mockResolvedValue([base]);
    api.getEvalDataset.mockResolvedValue({ dataset: base, cases: [] });
    api.listEvalRuns.mockResolvedValue([]);
    api.getTrajectoryEvalConfig.mockResolvedValue({ enabled: false });
    api.updateEvalDataset.mockResolvedValue({ ...base, toolExecutionEnabled: true });
    api.createEvalDataset.mockResolvedValue(base);
  });

  it("shows an expanded dataset's saved mode and saves a change with the whole definition", async () => {
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    expect(screen.getByRole("radio", { name: /Text only/ })).toBeChecked();

    fireEvent.click(screen.getByRole("radio", { name: /Full pipeline with tools/ }));
    api.listEvalDatasets.mockResolvedValue([{ ...base, toolExecutionEnabled: true }]);
    fireEvent.click(screen.getByRole("button", { name: "Save replay mode" }));

    await waitFor(() =>
      expect(api.updateEvalDataset).toHaveBeenCalledWith(AGENT, "d1", {
        name: "Billing FAQ",
        description: "Refund questions",
        passThreshold: 0.7,
        mode: "FULL",
        autoApproveTools: false,
      })
    );
    await waitFor(() => expect(screen.getByText("full pipeline")).toBeInTheDocument());
    expect(screen.getByText("Saved.")).toBeInTheDocument();
  });

  it("lets a pinned dataset go back to following the workspace default", async () => {
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    fireEvent.click(screen.getByRole("radio", { name: /Workspace default/ }));
    fireEvent.click(screen.getByRole("button", { name: "Save replay mode" }));
    await waitFor(() =>
      expect(api.updateEvalDataset).toHaveBeenCalledWith(
        AGENT,
        "d1",
        expect.objectContaining({ mode: "DEFAULT", autoApproveTools: false })
      )
    );
  });

  it("shows a dataset that pins nothing as following the default, with the mode a run takes now", async () => {
    const unpinned: EvalDatasetSummary = { ...base, toolExecutionEnabled: null, mode: null, effectiveMode: "FULL" };
    api.listEvalDatasets.mockResolvedValue([unpinned]);
    api.getEvalDataset.mockResolvedValue({ dataset: unpinned, cases: [] });
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    expect(screen.getByRole("radio", { name: /Workspace default/ })).toBeChecked();
    expect(screen.getByText("Currently: Full pipeline")).toBeInTheDocument();
    expect(screen.getByText("full pipeline")).toHaveAttribute("title", expect.stringMatching(/follows the workspace default/));
  });

  it("saves auto-approval only after the confirmation", async () => {
    api.listEvalDatasets.mockResolvedValue([{ ...base, toolExecutionEnabled: true }]);
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    fireEvent.click(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" }));
    expect(api.updateEvalDataset).not.toHaveBeenCalled();
    await confirmAutoApprove();
    fireEvent.click(screen.getByRole("button", { name: "Save replay mode" }));
    await waitFor(() =>
      expect(api.updateEvalDataset).toHaveBeenCalledWith(
        AGENT,
        "d1",
        expect.objectContaining({ mode: "FULL", autoApproveTools: true })
      )
    );
  });

  it("explains a refused auto-approval and leaves the switch off", async () => {
    api.listEvalDatasets.mockResolvedValue([{ ...base, toolExecutionEnabled: true }]);
    api.updateEvalDataset.mockRejectedValue(
      Object.assign(new Error("Auto-approving tool calls in evals is off for this workspace."), { status: 400 })
    );
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    fireEvent.click(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" }));
    await confirmAutoApprove();
    fireEvent.click(screen.getByRole("button", { name: "Save replay mode" }));
    await screen.findByText(/Not allowed in this workspace/);
    expect(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" })).toBeDisabled();
  });

  it("creates a dataset in the full pipeline with auto-approval", async () => {
    render(<EvalDatasetsSection agentId={AGENT} />);
    await screen.findByText("Billing FAQ");
    fireEvent.click(screen.getAllByRole("button", { name: /New dataset/ })[0]);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Tools set" } });
    fireEvent.click(screen.getByRole("radio", { name: /Full pipeline with tools/ }));
    fireEvent.click(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" }));
    await confirmAutoApprove();
    fireEvent.click(screen.getByRole("button", { name: "Create dataset" }));
    await waitFor(() =>
      expect(api.createEvalDataset).toHaveBeenCalledWith(
        AGENT,
        expect.objectContaining({ name: "Tools set", mode: "FULL", autoApproveTools: true })
      )
    );
  });

  it("creates a dataset that follows the workspace default unless a mode is chosen", async () => {
    render(<EvalDatasetsSection agentId={AGENT} />);
    await screen.findByText("Billing FAQ");
    fireEvent.click(screen.getAllByRole("button", { name: /New dataset/ })[0]);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Plain" } });
    expect(screen.getByRole("radio", { name: /Workspace default/ })).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Create dataset" }));
    await waitFor(() =>
      expect(api.createEvalDataset).toHaveBeenCalledWith(
        AGENT,
        expect.objectContaining({ mode: "DEFAULT", autoApproveTools: false })
      )
    );
  });

  it("creates a quick text-only dataset when that is chosen", async () => {
    render(<EvalDatasetsSection agentId={AGENT} />);
    await screen.findByText("Billing FAQ");
    fireEvent.click(screen.getAllByRole("button", { name: /New dataset/ })[0]);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Cheap" } });
    fireEvent.click(screen.getByRole("radio", { name: /Text only/ }));
    fireEvent.click(screen.getByRole("button", { name: "Create dataset" }));
    await waitFor(() =>
      expect(api.createEvalDataset).toHaveBeenCalledWith(
        AGENT,
        expect.objectContaining({ mode: "QUICK", autoApproveTools: false })
      )
    );
  });

  it("keeps the create form open and explains when auto-approval is refused on create", async () => {
    api.createEvalDataset.mockRejectedValue(
      Object.assign(new Error("Auto-approving tool calls in evals is off for this workspace."), { status: 400 })
    );
    render(<EvalDatasetsSection agentId={AGENT} />);
    await screen.findByText("Billing FAQ");
    fireEvent.click(screen.getAllByRole("button", { name: /New dataset/ })[0]);
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Tools set" } });
    fireEvent.click(screen.getByRole("radio", { name: /Full pipeline with tools/ }));
    fireEvent.click(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" }));
    await confirmAutoApprove();
    fireEvent.click(screen.getByRole("button", { name: "Create dataset" }));
    await screen.findByText(/Not allowed in this workspace/);
    expect(screen.getByRole("button", { name: "Create dataset" })).toBeInTheDocument();
  });

  it("badges an auto-approved run and shows each replayed tool call's status", async () => {
    api.listEvalRuns.mockResolvedValue([run({ autoApproved: true })]);
    api.getEvalRun.mockResolvedValue({
      run: run({ autoApproved: true }),
      caseResults: [
        {
          id: "c1",
          caseId: "k1",
          position: 0,
          userMessage: "Refund order 5",
          expectedOutput: null,
          expectedTools: [],
          agentResponse: "Done",
          toolCalls: [
            { toolName: "refund_lookup", input: "{}", output: "ok", status: "OK" },
            { toolName: "send_email", input: "{}", output: null, status: "AWAITING_APPROVAL" },
          ],
          scores: {},
          overallScore: 0.9,
          expectedToolsSatisfied: null,
          status: "SCORED",
          errorMessage: null,
          finishedAt: null,
        },
      ],
    });
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    expect(await screen.findByText("auto-approved")).toBeInTheDocument();
    // The run row's first button opens its case results.
    fireEvent.click(within(screen.getByText("auto-approved").parentElement as HTMLElement).getAllByRole("button")[0]);
    const calls = await screen.findByRole("list", { name: "Tool calls in this replay" });
    expect(within(calls).getByText("refund_lookup (ok)")).toBeInTheDocument();
    expect(within(calls).getByText("send_email (waiting for approval)")).toBeInTheDocument();
  });

  it("shows the mode of every run and the tools the sandbox simulated", async () => {
    api.listEvalRuns.mockResolvedValue([run({ id: "r-full", mode: "FULL" }), run({ id: "r-quick", mode: "QUICK" })]);
    api.getEvalRun.mockResolvedValue({
      run: run({ mode: "FULL" }),
      simulatedTools: ["send_email", "remember_fact"],
      caseResults: [
        {
          id: "c1",
          caseId: "k1",
          position: 0,
          userMessage: "Email the customer",
          expectedOutput: null,
          expectedTools: [],
          agentResponse: "Sent",
          toolCalls: [{ toolName: "send_email", input: "{}", output: "simulated", status: "SIMULATED" }],
          scores: {},
          overallScore: 0.9,
          expectedToolsSatisfied: null,
          status: "SCORED",
          errorMessage: null,
          finishedAt: null,
        },
      ],
    });
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    const history = await screen.findByText("Run history");
    const runs = history.parentElement as HTMLElement;
    expect(within(runs).getByText("full pipeline", { selector: "[title]" })).toBeInTheDocument();
    expect(within(runs).getByText("quick", { selector: "[title]" })).toBeInTheDocument();

    fireEvent.click(within(within(runs).getByText("full pipeline", { selector: "[title]" }).parentElement as HTMLElement).getAllByRole("button")[0]);
    expect(await screen.findByTestId("run-simulated-tools")).toHaveTextContent(
      "Simulated, not executed in this run (they could change data or reach outside the platform): send_email, remember_fact."
    );
    const calls = await screen.findByRole("list", { name: "Tool calls in this replay" });
    expect(within(calls).getByText("send_email (simulated, not executed)")).toBeInTheDocument();
  });

  it("names no mode on a run unless the run options choose one", async () => {
    api.triggerEvalRun.mockResolvedValue(run());
    render(<EvalDatasetsSection agentId={AGENT} />);
    await screen.findByText("Billing FAQ");
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    await waitFor(() => expect(api.triggerEvalRun).toHaveBeenCalledTimes(1));
    expect(api.triggerEvalRun.mock.calls[0][2].mode).toBeUndefined();
  });

  it("runs once in quick mode when the run options say so, and goes back to the dataset's setting", async () => {
    api.triggerEvalRun.mockResolvedValue(run());
    render(<EvalDatasetsSection agentId={AGENT} />);
    await expandFirst();
    fireEvent.click(screen.getByTitle("Judge model, repeats and budget for the next run"));
    expect(screen.getByText(/Full pipeline runs the agent with its tools/)).toBeInTheDocument();

    pickOption("Replay mode for the next run", "Quick (text only)");
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    await waitFor(() => expect(api.triggerEvalRun).toHaveBeenCalledTimes(1));
    expect(api.triggerEvalRun).toHaveBeenLastCalledWith(AGENT, "d1", expect.objectContaining({ mode: "QUICK" }));

    pickOption("Replay mode for the next run", "Full pipeline");
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    await waitFor(() => expect(api.triggerEvalRun).toHaveBeenCalledTimes(2));
    expect(api.triggerEvalRun).toHaveBeenLastCalledWith(AGENT, "d1", expect.objectContaining({ mode: "FULL" }));

    pickOption("Replay mode for the next run", "Use the dataset's setting");
    fireEvent.click(screen.getByRole("button", { name: "Run" }));
    await waitFor(() => expect(api.triggerEvalRun).toHaveBeenCalledTimes(3));
    expect(api.triggerEvalRun.mock.calls[2][2].mode).toBeUndefined();
  });
});
