import { beforeEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const getTrajectoryEvalConfig = vi.hoisted(() => vi.fn());
vi.mock("../../../lib/api", () => ({ api: { getTrajectoryEvalConfig } }));
vi.mock("../../../lib/i18n", async () => {
  const en = (await import("../../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import DatasetModeControls from "./DatasetModeControls";
import type { DatasetModeValue } from "./datasetMode";

let agentCounter = 0;
const freshAgent = () => `agent-${++agentCounter}`;

function Harness({
  initial,
  agentId,
  refusal = null,
  workspaceDefault = null,
  onChange,
}: {
  initial: DatasetModeValue;
  agentId: string;
  refusal?: string | null;
  workspaceDefault?: "FULL" | "QUICK" | null;
  onChange?: (v: DatasetModeValue) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <DatasetModeControls
      agentId={agentId}
      value={value}
      refusal={refusal}
      workspaceDefault={workspaceDefault}
      onChange={(v) => {
        setValue(v);
        onChange?.(v);
      }}
    />
  );
}

const DEFAULT: DatasetModeValue = { toolExecutionEnabled: null, autoApproveTools: false };
const TEXT: DatasetModeValue = { toolExecutionEnabled: false, autoApproveTools: false };
const FULL: DatasetModeValue = { toolExecutionEnabled: true, autoApproveTools: false };
const AUTO: DatasetModeValue = { toolExecutionEnabled: true, autoApproveTools: true };

const autoSwitch = () => screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" });

describe("DatasetModeControls", () => {
  beforeEach(() => {
    getTrajectoryEvalConfig.mockReset();
    getTrajectoryEvalConfig.mockResolvedValue({ enabled: false });
  });

  it("offers the workspace default, the full pipeline and text only as labelled choices", () => {
    render(<Harness initial={TEXT} agentId={freshAgent()} />);
    const group = screen.getByRole("radiogroup", { name: "How this dataset is replayed" });
    expect(group).toBeInTheDocument();
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    expect(screen.getByRole("radio", { name: /Workspace default/ })).not.toBeChecked();
    expect(screen.getByRole("radio", { name: /Text only/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /Full pipeline with tools/ })).not.toBeChecked();
  });

  it("selects the workspace default when the dataset pins nothing, and says what that is right now", () => {
    render(<Harness initial={DEFAULT} agentId={freshAgent()} workspaceDefault="FULL" />);
    expect(screen.getByRole("radio", { name: /Workspace default/ })).toBeChecked();
    expect(screen.getByText("Currently: Full pipeline")).toBeInTheDocument();
    expect(autoSwitch()).toBeEnabled();
    expect(screen.getByTestId("mode-full-note")).toBeInTheDocument();
  });

  it("explains the workspace default in general terms when it is not known", () => {
    render(<Harness initial={DEFAULT} agentId={freshAgent()} />);
    expect(screen.getByText(/Follows your workspace's setting/)).toBeInTheDocument();
    expect(autoSwitch()).toBeEnabled();
  });

  it("treats a workspace default of quick like quick: no tools run, so auto-approval is off and says why", () => {
    render(<Harness initial={DEFAULT} agentId={freshAgent()} workspaceDefault="QUICK" />);
    expect(screen.getByText("Currently: Quick (text only)")).toBeInTheDocument();
    expect(autoSwitch()).toBeDisabled();
    expect(screen.queryByTestId("mode-full-note")).not.toBeInTheDocument();
  });

  it("choosing the workspace default pins nothing and keeps an auto-approval that was on", () => {
    const onChange = vi.fn();
    render(<Harness initial={AUTO} agentId={freshAgent()} onChange={onChange} />);
    fireEvent.click(screen.getByRole("radio", { name: /Workspace default/ }));
    expect(onChange).toHaveBeenLastCalledWith({ toolExecutionEnabled: null, autoApproveTools: true });
  });

  it("turning auto-approval on under the workspace default does not pin the full pipeline", async () => {
    const onChange = vi.fn();
    render(<Harness initial={DEFAULT} agentId={freshAgent()} workspaceDefault="FULL" onChange={onChange} />);
    fireEvent.click(autoSwitch());
    await screen.findByRole("alertdialog");
    fireEvent.click(screen.getByRole("button", { name: "Turn on auto-approval" }));
    await waitFor(() => expect(onChange).toHaveBeenCalledWith({ toolExecutionEnabled: null, autoApproveTools: true }));
  });

  it("disables auto-approval in text-only mode and says why", () => {
    render(<Harness initial={TEXT} agentId={freshAgent()} />);
    expect(autoSwitch()).toBeDisabled();
    expect(screen.getByTestId("auto-approve-disabled-reason")).toHaveTextContent(/Switch on the full pipeline first/);
  });

  it("always shows the non-production warning next to the switch", () => {
    render(<Harness initial={TEXT} agentId={freshAgent()} />);
    expect(screen.getByTestId("auto-approve-warning")).toHaveTextContent(/For non-production workspaces only/);
    expect(screen.getByTestId("auto-approve-warning")).toHaveTextContent(/Never turn this on where tools reach real customers or data/);
  });

  it("choosing the full pipeline enables the switch and says what runs for real and what is simulated", () => {
    const onChange = vi.fn();
    render(<Harness initial={TEXT} agentId={freshAgent()} onChange={onChange} />);
    fireEvent.click(screen.getByRole("radio", { name: /Full pipeline with tools/ }));
    expect(onChange).toHaveBeenLastCalledWith(FULL);
    expect(autoSwitch()).toBeEnabled();
    expect(screen.getByTestId("mode-full-note")).toHaveTextContent(/Tools that only read run for real/);
    expect(screen.getByTestId("mode-full-note")).toHaveTextContent(/is simulated and listed in the run/);
  });

  it("asks for confirmation, stating the consequence, before auto-approval is turned on", async () => {
    const onChange = vi.fn();
    render(<Harness initial={FULL} agentId={freshAgent()} onChange={onChange} />);
    fireEvent.click(autoSwitch());
    expect(onChange).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent(/approved automatically/);
    expect(dialog).toHaveTextContent(/approved tools that could change something are still simulated/);
    fireEvent.click(screen.getByRole("button", { name: "Turn on auto-approval" }));
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(AUTO));
    expect(autoSwitch()).toHaveAttribute("aria-checked", "true");
  });

  it("cancelling the confirmation leaves auto-approval off", async () => {
    const onChange = vi.fn();
    render(<Harness initial={FULL} agentId={freshAgent()} onChange={onChange} />);
    fireEvent.click(autoSwitch());
    await screen.findByRole("alertdialog");
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument());
    expect(onChange).not.toHaveBeenCalled();
    expect(autoSwitch()).toHaveAttribute("aria-checked", "false");
  });

  it("turns auto-approval off without a confirmation", () => {
    const onChange = vi.fn();
    render(<Harness initial={AUTO} agentId={freshAgent()} onChange={onChange} />);
    fireEvent.click(autoSwitch());
    expect(onChange).toHaveBeenCalledWith(FULL);
  });

  it("going back to text only also drops auto-approval", () => {
    const onChange = vi.fn();
    render(<Harness initial={AUTO} agentId={freshAgent()} onChange={onChange} />);
    fireEvent.click(screen.getByRole("radio", { name: /Text only/ }));
    expect(onChange).toHaveBeenLastCalledWith(TEXT);
  });

  it("keeps the switch off and disabled, with the server's sentence, after a refusal", () => {
    render(<Harness initial={FULL} agentId={freshAgent()} refusal="Auto-approving tool calls in evals is off for this workspace." />);
    expect(autoSwitch()).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("Not allowed in this workspace: Auto-approving tool calls in evals is off for this workspace.");
  });

  it("shows trajectory checks read-only: on when the deployment has them", async () => {
    getTrajectoryEvalConfig.mockResolvedValue({ enabled: true, modes: ["exact"], maxSteps: 50, maxArgsPerStep: 20, maxActualSteps: 200 });
    render(<Harness initial={TEXT} agentId={freshAgent()} />);
    const sw = screen.getByRole("switch", { name: "Trajectory checks" });
    expect(sw).toBeDisabled();
    await waitFor(() => expect(sw).toHaveAttribute("aria-checked", "true"));
    expect(screen.getByText(/switched on for the whole deployment by your administrator, not per dataset/)).toBeInTheDocument();
    // text-only: trajectory cases would not be scored
    expect(screen.getByTestId("trajectory-text-only-note")).toBeInTheDocument();
  });

  it("does not warn about trajectory cases in the full pipeline", async () => {
    getTrajectoryEvalConfig.mockResolvedValue({ enabled: true });
    render(<Harness initial={FULL} agentId={freshAgent()} />);
    await waitFor(() => expect(screen.getByRole("switch", { name: "Trajectory checks" })).toHaveAttribute("aria-checked", "true"));
    expect(screen.queryByTestId("trajectory-text-only-note")).not.toBeInTheDocument();
  });

  it("says trajectory checks are not available when the deployment has them off", async () => {
    render(<Harness initial={FULL} agentId={freshAgent()} />);
    await waitFor(() => expect(getTrajectoryEvalConfig).toHaveBeenCalled());
    expect(screen.getByRole("switch", { name: "Trajectory checks" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByText("Not available")).toBeInTheDocument();
  });
});
