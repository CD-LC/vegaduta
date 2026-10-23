import { beforeEach, describe, expect, it, vi } from "vitest";
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

import DatasetModePanel from "./DatasetModePanel";
import type { EvalDatasetSummary } from "../evalDatasetsApi";

const dataset = (patch: Partial<EvalDatasetSummary> = {}): EvalDatasetSummary => ({
  id: "d1",
  agentId: "panel-agent",
  name: "Billing",
  description: null,
  passThreshold: 0.7,
  toolExecutionEnabled: false,
  autoApproveTools: false,
  caseCount: 3,
  createdBy: null,
  createdAt: "2026-10-01T00:00:00Z",
  updatedAt: "2026-10-01T00:00:00Z",
  ...patch,
});

const saveButton = () => screen.getByRole("button", { name: "Save replay mode" });

describe("DatasetModePanel", () => {
  beforeEach(() => {
    getTrajectoryEvalConfig.mockReset();
    getTrajectoryEvalConfig.mockResolvedValue({ enabled: false });
  });

  it("starts clean: nothing to save until the mode changes", () => {
    render(<DatasetModePanel agentId="panel-agent" dataset={dataset()} save={vi.fn()} />);
    expect(saveButton()).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Discard changes" })).not.toBeInTheDocument();
  });

  it("saves a changed mode explicitly and confirms", async () => {
    const save = vi.fn().mockResolvedValue(undefined);
    render(<DatasetModePanel agentId="panel-agent" dataset={dataset()} save={save} />);
    fireEvent.click(screen.getByRole("radio", { name: /Full pipeline with tools/ }));
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    expect(save).not.toHaveBeenCalled();
    fireEvent.click(saveButton());
    await waitFor(() => expect(save).toHaveBeenCalledWith({ toolExecutionEnabled: true, autoApproveTools: false }));
  });

  it("discards an unsaved change", () => {
    render(<DatasetModePanel agentId="panel-agent" dataset={dataset()} save={vi.fn()} />);
    fireEvent.click(screen.getByRole("radio", { name: /Full pipeline with tools/ }));
    fireEvent.click(screen.getByRole("button", { name: "Discard changes" }));
    expect(screen.getByRole("radio", { name: /Text only/ })).toBeChecked();
    expect(saveButton()).toBeDisabled();
  });

  it("shows the saved auto-approval and the saved full-pipeline mode", () => {
    render(<DatasetModePanel agentId="panel-agent" dataset={dataset({ toolExecutionEnabled: true, autoApproveTools: true })} save={vi.fn()} />);
    expect(screen.getByRole("radio", { name: /Full pipeline with tools/ })).toBeChecked();
    expect(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" })).toHaveAttribute("aria-checked", "true");
  });

  it("rolls auto-approval back and explains when the server refuses it", async () => {
    const refusal = Object.assign(new Error("Auto-approving tool calls in evals is off for this workspace."), { status: 400 });
    const save = vi.fn().mockRejectedValue(refusal);
    render(<DatasetModePanel agentId="panel-agent" dataset={dataset({ toolExecutionEnabled: true })} save={save} />);
    fireEvent.click(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" }));
    fireEvent.click(await screen.findByRole("button", { name: "Turn on auto-approval" }));
    await waitFor(() => expect(saveButton()).toBeEnabled());
    fireEvent.click(saveButton());
    await screen.findByText(/Not allowed in this workspace: Auto-approving tool calls/);
    const sw = screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" });
    expect(sw).toHaveAttribute("aria-checked", "false");
    expect(sw).toBeDisabled();
  });

  it("reports any other failure and keeps the draft", async () => {
    const save = vi.fn().mockRejectedValue(Object.assign(new Error("Server exploded"), { status: 500 }));
    render(<DatasetModePanel agentId="panel-agent" dataset={dataset()} save={save} />);
    fireEvent.click(screen.getByRole("radio", { name: /Full pipeline with tools/ }));
    fireEvent.click(saveButton());
    await screen.findByText("Server exploded");
    expect(screen.getByRole("radio", { name: /Full pipeline with tools/ })).toBeChecked();
  });
});
