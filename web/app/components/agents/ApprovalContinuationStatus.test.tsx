import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key) => ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key),
  };
});

import ApprovalContinuationStatus, {
  COMPLETED_AUTO_CLOSE_MS,
  LONG_WAIT_MS,
  STILL_WORKING_MS,
} from "./ApprovalContinuationStatus";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const base: {
  continuing: boolean;
  problem: "notStarted" | "timedOut" | null;
  outcome: "completed" | "failed" | null;
  dismiss: () => void;
} = { continuing: false, problem: null, outcome: null, dismiss: vi.fn() };

describe("ApprovalContinuationStatus", () => {
  it("renders nothing when there is nothing to say", () => {
    const { container } = render(<ApprovalContinuationStatus continuation={{ ...base }} />);
    expect(container.firstChild).toBeNull();
  });

  it("says the agent is carrying on", () => {
    render(<ApprovalContinuationStatus continuation={{ ...base, continuing: true }} />);
    expect(screen.getByRole("status")).toHaveTextContent("The agent is carrying on with the task.");
  });

  it("keeps the carrying-on line alive: stages, elapsed time and a still-working note as time passes", () => {
    vi.useFakeTimers();
    const since = Date.now();
    const { rerender } = render(
      <ApprovalContinuationStatus continuation={{ ...base, continuing: true, since, phase: "waiting" }} />
    );
    const line = screen.getByTestId("approval-continuing");
    expect(line).toHaveAttribute("data-phase", "waiting");
    expect(screen.getByTestId("approval-continuing-hint")).toHaveTextContent("picking the task back up");
    expect(screen.getByTestId("approval-continuing-elapsed")).toHaveTextContent("0 s");
    const stages = Array.from(line.querySelectorAll("li")).map((li) => li.getAttribute("data-state"));
    expect(stages).toEqual(["done", "active", "todo"]);

    rerender(<ApprovalContinuationStatus continuation={{ ...base, continuing: true, since, phase: "running" }} />);
    expect(screen.getByTestId("approval-continuing-hint")).toHaveTextContent("working on the next step");
    expect(Array.from(line.querySelectorAll("li")).map((li) => li.getAttribute("data-state"))).toEqual([
      "done",
      "done",
      "active",
    ]);

    act(() => {
      vi.advanceTimersByTime(STILL_WORKING_MS + 1000);
    });
    expect(screen.getByTestId("approval-continuing-hint")).toHaveTextContent("Still working");
    expect(screen.getByTestId("approval-continuing-elapsed")).toHaveTextContent("31 s");
    act(() => {
      vi.advanceTimersByTime(LONG_WAIT_MS);
    });
    expect(screen.getByTestId("approval-continuing-hint")).toHaveTextContent("taking longer than usual");
  });

  it("shows a finished receipt that closes itself", () => {
    vi.useFakeTimers();
    const dismiss = vi.fn();
    render(<ApprovalContinuationStatus continuation={{ ...base, outcome: "completed", dismiss }} />);
    expect(screen.getByTestId("approval-continuation-completed")).toHaveTextContent("finished carrying on");
    expect(dismiss).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(COMPLETED_AUTO_CLOSE_MS + 10);
    });
    expect(dismiss).toHaveBeenCalledTimes(1);
  });

  it("keeps a failure on screen with its reason and offers Retry only when the host can retry", () => {
    const retry = vi.fn();
    const dismiss = vi.fn();
    const { rerender } = render(<ApprovalContinuationStatus continuation={{ ...base, outcome: "failed", dismiss }} />);
    expect(screen.getByRole("alert")).toHaveTextContent("could not carry on");
    expect(screen.queryByTestId("approval-continuation-retry")).toBeNull();
    rerender(<ApprovalContinuationStatus continuation={{ ...base, outcome: "failed", dismiss, retry }} />);
    fireEvent.click(screen.getByTestId("approval-continuation-retry"));
    expect(retry).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId("approval-continuation-dismiss"));
    expect(dismiss).toHaveBeenCalledTimes(1);
  });

  it("keeps the original not-started and timed-out wording, with Retry when available", () => {
    const retry = vi.fn();
    const { rerender } = render(<ApprovalContinuationStatus continuation={{ ...base, problem: "notStarted", retry }} />);
    expect(screen.getByTestId("approval-continuation-notStarted")).toHaveTextContent("was not restarted automatically");
    expect(screen.getByTestId("approval-continuation-retry")).toBeInTheDocument();
    rerender(<ApprovalContinuationStatus continuation={{ ...base, problem: "timedOut" }} />);
    expect(screen.getByTestId("approval-continuation-timedOut")).toHaveTextContent("has not finished carrying on yet");
  });
});
