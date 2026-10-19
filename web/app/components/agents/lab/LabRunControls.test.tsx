// The shared run controls: which sentence the estimate shows, every reason
// the Run button can be unavailable (and that a plan lock is an upgrade
// affordance, not a dead button), and the one action LabRunStatus offers in
// each phase of a run.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

vi.mock("../../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

import { LabEstimate, LabRunButton, LabRunStatus } from "./LabRunControls";
import type { LabCapabilities, LabRun, LabRunStatus as RunStatus } from "./labApi";
import type { LabRunPhase, UseLabRun } from "./useLabRun";

const ALLOWED: LabCapabilities = {
  allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60,
};
const LOCKED: LabCapabilities = { ...ALLOWED, allowed: false, reason: "PLAN" };

function runOf(status: RunStatus, extra: Partial<LabRun> = {}): LabRun {
  return {
    runId: "run-1", agentId: "agent-1", kind: "KNOCKOUT", status,
    plannedCalls: 10, completedCalls: 4, cancelRequested: false,
    errorMessage: null, createdAtMs: 1, updatedAtMs: 1, finishedAtMs: null,
    cells: [], similarity: null,
    ...extra,
  };
}

function labOf(phase: LabRunPhase, extra: Partial<UseLabRun<unknown>> = {}): UseLabRun<unknown> {
  return {
    run: null, context: null, phase, error: null, cancelling: false,
    start: vi.fn(async () => {}),
    cancel: vi.fn(async () => {}),
    resume: vi.fn(),
    reset: vi.fn(),
    ...extra,
  };
}

describe("LabEstimate", () => {
  it("says there is nothing to run for zero calls", () => {
    render(<LabEstimate calls={0} cap={120} />);
    expect(screen.getByText("estimateNone")).toBeInTheDocument();
    expect(screen.getByText("estimateUsageNote")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("uses the singular sentence for exactly one call", () => {
    render(<LabEstimate calls={1} cap={120} />);
    expect(screen.getByText("estimateCallsOne")).toBeInTheDocument();
  });

  it("states the count, with the breakdown after it", () => {
    render(<LabEstimate calls={15} cap={120} breakdown="5 samples and 10 comparisons." />);
    expect(screen.getByText(/estimateCalls:\{"count":15\} 5 samples and 10 comparisons\./)).toBeInTheDocument();
  });

  it("flags a count over the cap, and not one exactly at it", () => {
    const { rerender } = render(<LabEstimate calls={120} cap={120} />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    rerender(<LabEstimate calls={121} cap={120} />);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent('estimateOverCap:{"cap":120}');
    expect(alert).toHaveClass("text-destructive");
  });
});

describe("LabRunButton", () => {
  it("runs when allowed and within the cap", () => {
    const onRun = vi.fn();
    render(<LabRunButton label="Run" calls={10} capabilities={ALLOWED} onRun={onRun} />);
    const button = screen.getByRole("button", { name: "Run" });
    expect(button).toBeEnabled();
    fireEvent.click(button);
    expect(onRun).toHaveBeenCalledTimes(1);
  });

  it("is disabled, with the reason as its title, when capabilities are unknown", () => {
    const onRun = vi.fn();
    render(<LabRunButton label="Run" calls={10} capabilities={null} onRun={onRun} />);
    const button = screen.getByRole("button", { name: "Run" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("title", "capabilitiesUnsupported");
    fireEvent.click(button);
    expect(onRun).not.toHaveBeenCalled();
  });

  it("is upgrade-locked rather than natively disabled on a plan that may not run", () => {
    const onRun = vi.fn();
    render(<LabRunButton label="Run" calls={10} capabilities={LOCKED} onRun={onRun} />);
    const button = screen.getByRole("button", { name: "Run" });
    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).toHaveAttribute("data-upgrade-locked");
    expect(button).not.toBeDisabled();
    fireEvent.click(button);
    expect(onRun).not.toHaveBeenCalled();
  });

  it("is disabled for zero calls and for a count over the server cap", () => {
    const { rerender } = render(<LabRunButton label="Run" calls={0} capabilities={ALLOWED} onRun={() => {}} />);
    expect(screen.getByRole("button", { name: "Run" })).toBeDisabled();
    rerender(<LabRunButton label="Run" calls={121} capabilities={ALLOWED} onRun={() => {}} />);
    expect(screen.getByRole("button", { name: "Run" })).toBeDisabled();
    rerender(<LabRunButton label="Run" calls={120} capabilities={ALLOWED} onRun={() => {}} />);
    expect(screen.getByRole("button", { name: "Run" })).toBeEnabled();
  });

  it("shows the caller's reason when the caller disables it", () => {
    render(
      <LabRunButton label="Run" calls={10} capabilities={ALLOWED} disabled disabledReason="Add at least one question." onRun={() => {}} />
    );
    const button = screen.getByRole("button", { name: "Run" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("title", "Add at least one question.");
  });
});

describe("LabRunStatus", () => {
  it("renders nothing while idle", () => {
    const { container } = render(<LabRunStatus lab={labOf("idle")} ttlMinutes={60} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("starting: the status only - no progress, nothing to press", () => {
    render(<LabRunStatus lab={labOf("starting")} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent("statusStarting");
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("running: progress with aria values, and Cancel", () => {
    const lab = labOf("polling", { run: runOf("RUNNING") });
    render(<LabRunStatus lab={lab} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent("statusRunning");
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAttribute("aria-valuemin", "0");
    expect(bar).toHaveAttribute("aria-valuemax", "10");
    expect(bar).toHaveAttribute("aria-valuenow", "4");
    expect(screen.getByText('progress:{"done":4,"total":10}')).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "cancel" }));
    expect(lab.cancel).toHaveBeenCalledTimes(1);
  });

  it("queued reads as waiting", () => {
    render(<LabRunStatus lab={labOf("polling", { run: runOf("QUEUED", { completedCalls: 0 }) })} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent("statusQueued");
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "0");
  });

  it("cancelling: the button says so and cannot be pressed twice", () => {
    render(<LabRunStatus lab={labOf("polling", { run: runOf("RUNNING"), cancelling: true })} ttlMinutes={60} />);
    expect(screen.getByRole("button", { name: "cancelling" })).toBeDisabled();
  });

  it("a restored run with no response yet still offers Cancel, without a progress bar", () => {
    render(<LabRunStatus lab={labOf("polling")} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent("statusRunning");
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "cancel" })).toBeInTheDocument();
  });

  it("lost: offers Check again, Cancel (the run may still be spending) and a way out", () => {
    const lab = labOf("lost", { run: runOf("RUNNING") });
    render(<LabRunStatus lab={lab} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent("statusLost");
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "resume" }));
    expect(lab.resume).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "cancel" }));
    expect(lab.cancel).toHaveBeenCalledTimes(1);
    // Not the plain "Clear results": from lost, clearing also stops the run.
    expect(screen.queryByRole("button", { name: "runAgain" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "stopAndClear" }));
    expect(lab.reset).toHaveBeenCalledTimes(1);
  });

  it("done: finished, no progress bar, Clear results resets", () => {
    const lab = labOf("done", { run: runOf("SUCCEEDED", { completedCalls: 10 }) });
    render(<LabRunStatus lab={lab} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent("statusSucceeded");
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "runAgain" }));
    expect(lab.reset).toHaveBeenCalledTimes(1);
  });

  it("cancelled and failed runs say which, and a failure carries the server's sentence", () => {
    const { rerender } = render(<LabRunStatus lab={labOf("done", { run: runOf("CANCELLED") })} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent("statusCancelled");
    rerender(
      <LabRunStatus lab={labOf("done", { run: runOf("FAILED", { errorMessage: "The run passed its 20 minute limit." }) })} ttlMinutes={60} />
    );
    expect(screen.getByRole("status")).toHaveTextContent('statusFailed:{"reason":"The run passed its 20 minute limit."}');
    expect(screen.getByRole("status")).toHaveClass("text-destructive");
  });

  it("expired: states how long results are kept", () => {
    const lab = labOf("expired");
    render(<LabRunStatus lab={lab} ttlMinutes={45} />);
    expect(screen.getByRole("status")).toHaveTextContent('statusExpired:{"minutes":45}');
    fireEvent.click(screen.getByRole("button", { name: "runAgain" }));
    expect(lab.reset).toHaveBeenCalledTimes(1);
  });

  it("error: shows why the run could not start", () => {
    render(<LabRunStatus lab={labOf("error", { error: "You already have a lab run in progress." })} ttlMinutes={60} />);
    expect(screen.getByRole("status")).toHaveTextContent('statusError:{"reason":"You already have a lab run in progress."}');
    expect(screen.getByRole("button", { name: "runAgain" })).toBeInTheDocument();
  });
});
