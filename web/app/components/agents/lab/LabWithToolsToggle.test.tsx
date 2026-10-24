import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, renderHook, act, screen, waitFor } from "@testing-library/react";

vi.mock("../../../lib/i18n", async () => {
  const en = (await import("../../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import LabWithToolsToggle, { useLabWithTools, type LabWithTools } from "./LabWithToolsToggle";
import type { LabCapabilities } from "./labApi";

const CAPS: LabCapabilities = {
  allowed: true, reason: null, maxCells: 60, maxCallsPerRun: 120, maxSystemPrompts: 16, maxSimilarityCells: 8, runTtlMinutes: 60,
};

const state = (patch: Partial<LabWithTools> = {}): LabWithTools => ({
  withTools: false,
  setWithTools: vi.fn(),
  refusal: null,
  unavailable: false,
  ...patch,
});

const sw = () => screen.getByRole("switch", { name: "Run with the agent's tools" });

describe("LabWithToolsToggle", () => {
  it("is off by default, labelled, and explains what it does", () => {
    render(<LabWithToolsToggle state={state()} />);
    expect(sw()).toHaveAttribute("aria-checked", "false");
    expect(sw()).toBeEnabled();
    expect(screen.getByText(/same tool pipeline a chat turn uses/)).toBeInTheDocument();
  });

  it("asks for confirmation, naming the real effects, before turning on", async () => {
    const s = state();
    render(<LabWithToolsToggle state={s} />);
    fireEvent.click(sw());
    expect(s.setWithTools).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveTextContent(/real effects such as sending a message or writing data/);
    fireEvent.click(screen.getByRole("button", { name: "Use tools" }));
    await waitFor(() => expect(s.setWithTools).toHaveBeenCalledWith(true));
  });

  it("turns off with one click", () => {
    const s = state({ withTools: true });
    render(<LabWithToolsToggle state={s} />);
    expect(screen.getByText(/Tools will run for real in this run/)).toBeInTheDocument();
    fireEvent.click(sw());
    expect(s.setWithTools).toHaveBeenCalledWith(false);
  });

  it("is visibly disabled with an explanation when the install does not allow it", () => {
    render(<LabWithToolsToggle state={state({ unavailable: true })} />);
    expect(sw()).toBeDisabled();
    expect(screen.getByTestId("lab-tools-unavailable")).toHaveTextContent("turned off on this install. Ask an administrator to enable it.");
  });

  it("is disabled with the server's sentence after a refusal", () => {
    render(<LabWithToolsToggle state={state({ refusal: "Running the lab with the agent's tools is turned off on this install (app.agent-lab.with-tools.enabled)." })} />);
    expect(sw()).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent(/This run was refused: .*app\.agent-lab\.with-tools\.enabled/);
  });

  it("can be disabled while a run is live", () => {
    render(<LabWithToolsToggle state={state()} disabled />);
    expect(sw()).toBeDisabled();
  });
});

describe("useLabWithTools", () => {
  it("asks for tools only when switched on", () => {
    const { result } = renderHook(() => useLabWithTools({ phase: "idle", error: null }, CAPS));
    expect(result.current.withTools).toBe(false);
    act(() => result.current.setWithTools(true));
    expect(result.current.withTools).toBe(true);
  });

  it("never asks for tools where capabilities say the mode is off", () => {
    const { result } = renderHook(() => useLabWithTools({ phase: "idle", error: null }, { ...CAPS, withToolsAvailable: false }));
    act(() => result.current.setWithTools(true));
    expect(result.current.unavailable).toBe(true);
    expect(result.current.withTools).toBe(false);
  });

  it("stays usable when capabilities do not say, and keeps the unknown case optimistic", () => {
    const { result } = renderHook(() => useLabWithTools({ phase: "idle", error: null }, CAPS));
    expect(result.current.unavailable).toBe(false);
  });

  it("switches off and keeps the reason when the server refuses a run for tools", () => {
    const lab = { phase: "idle", error: null as string | null };
    const { result, rerender } = renderHook(() => useLabWithTools(lab, CAPS));
    act(() => result.current.setWithTools(true));
    lab.phase = "error";
    lab.error = "Running the lab with the agent's tools is turned off on this install (app.agent-lab.with-tools.enabled).";
    rerender();
    expect(result.current.withTools).toBe(false);
    expect(result.current.refusal).toContain("with-tools");
    act(() => result.current.setWithTools(true));
    expect(result.current.withTools).toBe(false);
  });

  it("believes a core that reports the mode is available, and does not guess from error text", () => {
    const lab = { phase: "idle", error: null as string | null };
    const { result, rerender } = renderHook(() => useLabWithTools(lab, { ...CAPS, withToolsAvailable: true }));
    act(() => result.current.setWithTools(true));
    lab.phase = "error";
    lab.error = "A cell failed: tool with-tools-demo timed out.";
    rerender();
    expect(result.current.unavailable).toBe(false);
    expect(result.current.refusal).toBeNull();
    expect(result.current.withTools).toBe(true);
  });

  it("ignores an unrelated run error", () => {
    const lab = { phase: "idle", error: null as string | null };
    const { result, rerender } = renderHook(() => useLabWithTools(lab, CAPS));
    act(() => result.current.setWithTools(true));
    lab.phase = "error";
    lab.error = "The model call timed out.";
    rerender();
    expect(result.current.withTools).toBe(true);
    expect(result.current.refusal).toBeNull();
  });
});
