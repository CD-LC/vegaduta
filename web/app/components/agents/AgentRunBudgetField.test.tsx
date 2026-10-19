// Per-agent run cap field (trust T5). Locks in: the validation mirrors the
// server's reject-not-clamp rules, the field hides itself when the route is
// missing or per-agent caps are switched off, and Save / Clear send exactly
// the value (or null) the server contract expects.

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const getAgentRunBudget = vi.fn();
const setAgentRunBudget = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getAgentRunBudget() {
      return getAgentRunBudget;
    },
    get setAgentRunBudget() {
      return setAgentRunBudget;
    },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

import AgentRunBudgetField from "./AgentRunBudgetField";
import {
  formatRunBudgetUsd,
  normalizeRunBudget,
  runBudgetInputText,
  validateRunBudgetInput,
} from "./agentRunBudget";

const BASE: Record<string, unknown> = {
  overrideEnabled: true,
  globalCapUsd: 2,
  agentCapUsd: null,
  effectiveCapUsd: 2,
  maxAgentCapUsd: 2,
  perBranchEstimateUsd: 0.048,
  scope: "delegation tools only",
  estimate: true,
};

beforeEach(() => {
  getAgentRunBudget.mockReset();
  setAgentRunBudget.mockReset();
});

describe("validateRunBudgetInput", () => {
  it("accepts a positive value within the ceiling", () => {
    expect(validateRunBudgetInput("1.5", 2)).toEqual({ ok: true, value: 1.5 });
    expect(validateRunBudgetInput(" 2 ", 2)).toEqual({ ok: true, value: 2 });
    expect(validateRunBudgetInput(".25", 2)).toEqual({ ok: true, value: 0.25 });
    expect(validateRunBudgetInput("500", null)).toEqual({ ok: true, value: 500 });
  });

  it("rejects what the server rejects", () => {
    expect(validateRunBudgetInput("", 2)).toEqual({ ok: false, error: "empty" });
    expect(validateRunBudgetInput("abc", 2)).toEqual({ ok: false, error: "notNumber" });
    expect(validateRunBudgetInput("-1", 2)).toEqual({ ok: false, error: "notNumber" });
    expect(validateRunBudgetInput("1e2", null)).toEqual({ ok: false, error: "notNumber" });
    expect(validateRunBudgetInput("0", 2)).toEqual({ ok: false, error: "notPositive" });
    expect(validateRunBudgetInput("0.00001", 2)).toEqual({ ok: false, error: "tooManyDecimals" });
    expect(validateRunBudgetInput("2.01", 2)).toEqual({ ok: false, error: "aboveMax" });
    expect(validateRunBudgetInput("1000000", null)).toEqual({ ok: false, error: "tooLarge" });
  });

  it("does not count trailing zeros as decimal places", () => {
    expect(validateRunBudgetInput("1.50000", 2)).toEqual({ ok: true, value: 1.5 });
  });
});

describe("normalizeRunBudget / formatting", () => {
  it("returns null for a body that is not a run-budget response", () => {
    expect(normalizeRunBudget(null)).toBeNull();
    expect(normalizeRunBudget({ globalCapUsd: 2 })).toBeNull();
  });

  it("keeps a null agent cap null and reads numbers", () => {
    const b = normalizeRunBudget({ ...BASE, agentCapUsd: 1.5 });
    expect(b?.agentCapUsd).toBe(1.5);
    expect(normalizeRunBudget(BASE)?.agentCapUsd).toBeNull();
    expect(normalizeRunBudget({ overrideEnabled: true, maxAgentCapUsd: null })?.maxAgentCapUsd).toBeNull();
  });

  it("formats money with two to four decimals and input text without trailing zeros", () => {
    expect(formatRunBudgetUsd(2)).toBe("$2.00");
    expect(formatRunBudgetUsd(0.048)).toBe("$0.048");
    expect(runBudgetInputText(1.5)).toBe("1.5");
    expect(runBudgetInputText(null)).toBe("");
  });
});

describe("AgentRunBudgetField", () => {
  it("renders nothing when the route is missing (older core answers 404)", async () => {
    getAgentRunBudget.mockRejectedValue(Object.assign(new Error("Not found"), { status: 404 }));
    const { container } = render(<AgentRunBudgetField agentId="a1" />);
    await waitFor(() => expect(getAgentRunBudget).toHaveBeenCalledWith("a1"));
    expect(container.firstChild).toBeNull();
  });

  it("renders nothing when per-agent caps are switched off", async () => {
    getAgentRunBudget.mockResolvedValue({ ...BASE, overrideEnabled: false });
    const { container } = render(<AgentRunBudgetField agentId="a1" />);
    await waitFor(() => expect(getAgentRunBudget).toHaveBeenCalled());
    await waitFor(() => expect(container.firstChild).toBeNull());
  });

  it("shows the platform cap, the effective cap and the per-sub-agent estimate", async () => {
    getAgentRunBudget.mockResolvedValue(BASE);
    render(<AgentRunBudgetField agentId="a1" />);
    expect((await screen.findByTestId("agent-run-budget-effective")).textContent).toBe("effective:$2.00");
    expect(screen.getByTestId("agent-run-budget-platform").textContent).toBe("platformCap:$2.00");
    expect(screen.getByTestId("agent-run-budget-estimate").textContent).toBe("perBranch:$0.048");
    // No agent cap stored: nothing to clear, nothing valid to save yet.
    expect(screen.queryByTestId("agent-run-budget-clear")).toBeNull();
    expect((screen.getByTestId("agent-run-budget-save") as HTMLButtonElement).disabled).toBe(true);
  });

  it("blocks a value above the ceiling with an inline error and never calls the server", async () => {
    getAgentRunBudget.mockResolvedValue(BASE);
    render(<AgentRunBudgetField agentId="a1" />);
    const input = await screen.findByTestId("agent-run-budget-input");
    fireEvent.change(input, { target: { value: "5" } });
    expect(screen.getByTestId("agent-run-budget-invalid").textContent).toBe("errorAboveMax:$2.00");
    expect((screen.getByTestId("agent-run-budget-save") as HTMLButtonElement).disabled).toBe(true);
    expect(setAgentRunBudget).not.toHaveBeenCalled();
  });

  it("saves a valid value and reflects the server's answer", async () => {
    getAgentRunBudget.mockResolvedValue(BASE);
    setAgentRunBudget.mockResolvedValue({ ...BASE, agentCapUsd: 1.5, effectiveCapUsd: 1.5 });
    render(<AgentRunBudgetField agentId="a1" />);
    const input = await screen.findByTestId("agent-run-budget-input");
    fireEvent.change(input, { target: { value: "1.5" } });
    fireEvent.click(screen.getByTestId("agent-run-budget-save"));

    await waitFor(() => expect(setAgentRunBudget).toHaveBeenCalledWith("a1", 1.5));
    await waitFor(() =>
      expect(screen.getByTestId("agent-run-budget-effective").textContent).toBe("effective:$1.50")
    );
    expect(screen.getByTestId("agent-run-budget-saved").textContent).toBe("saved");
    expect(screen.getByTestId("agent-run-budget-clear")).toBeTruthy();
  });

  it("clears the agent cap by sending null", async () => {
    getAgentRunBudget.mockResolvedValue({ ...BASE, agentCapUsd: 1.5, effectiveCapUsd: 1.5 });
    setAgentRunBudget.mockResolvedValue(BASE);
    render(<AgentRunBudgetField agentId="a1" />);
    const input = (await screen.findByTestId("agent-run-budget-input")) as HTMLInputElement;
    expect(input.value).toBe("1.5");
    fireEvent.click(screen.getByTestId("agent-run-budget-clear"));

    await waitFor(() => expect(setAgentRunBudget).toHaveBeenCalledWith("a1", null));
    await waitFor(() => expect(input.value).toBe(""));
    expect(screen.getByTestId("agent-run-budget-saved").textContent).toBe("cleared");
  });

  it("shows the server's message when a save is refused", async () => {
    getAgentRunBudget.mockResolvedValue(BASE);
    setAgentRunBudget.mockRejectedValue(
      Object.assign(new Error("Per-agent run caps are switched off on this deployment"), { status: 409 })
    );
    render(<AgentRunBudgetField agentId="a1" />);
    fireEvent.change(await screen.findByTestId("agent-run-budget-input"), { target: { value: "1" } });
    fireEvent.click(screen.getByTestId("agent-run-budget-save"));
    expect((await screen.findByTestId("agent-run-budget-error")).textContent).toBe(
      "Per-agent run caps are switched off on this deployment"
    );
  });

  it("is read-only while the agent is edit-locked", async () => {
    getAgentRunBudget.mockResolvedValue({ ...BASE, agentCapUsd: 1.5, effectiveCapUsd: 1.5 });
    render(<AgentRunBudgetField agentId="a1" locked lockedTooltip="locked" />);
    const input = (await screen.findByTestId("agent-run-budget-input")) as HTMLInputElement;
    expect(input.disabled).toBe(true);
    expect((screen.getByTestId("agent-run-budget-clear") as HTMLButtonElement).disabled).toBe(true);
  });
});
