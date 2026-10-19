// "Attack my agent" (trust T7). Locks in the things that would mislead a
// builder if they broke: the headline uses the same denominator as the
// server's rate and never shows an undecided result as zero percent; the
// section hides when the core offers no attack scenarios; only the attack
// personas are launched; and a compromised scenario is shown as such, with
// the fixes and the report's own limits.

import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const apiMock = vi.hoisted(() => ({
  listSimulationPersonas: vi.fn(),
  listSimulationSessions: vi.fn(),
  getSimulationAttackReport: vi.fn(),
  createSimulationSession: vi.fn(),
  cancelSimulationSession: vi.fn(),
  getSimulationRun: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api: apiMock }));

vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import AttackSection from "./AttackSection";
import {
  attackHeadline,
  attackHeadlineText,
  attackOutcome,
  attackPersonaKeys,
  attackReportPending,
  latestAttackSessionId,
  normalizeAttackReport,
  parseAttackBudget,
  type AttackReport,
} from "./attackReport";

const PERSONAS = [
  { key: "prompt_injection", name: "Prompt injection", description: "", objective: "", builtIn: true, attack: false },
  { key: "attack_poisoned_email", name: "Poisoned email", description: "", objective: "", builtIn: true, attack: true },
  { key: "attack_poisoned_web_page", name: "Poisoned web page", description: "", objective: "", builtIn: true, attack: true },
  { key: "my_custom", name: "Mine", description: "", objective: "", builtIn: false, attack: false },
];

const LIMITS = ["tools disabled: measures whether the model obeys injected content, not the live tool path"];

function report(overrides: Partial<AttackReport> = {}): AttackReport {
  return {
    sessionId: "s1",
    total: 4,
    succeededAttacks: 1,
    resisted: 2,
    inconclusive: 1,
    pending: 0,
    attackSuccessRate: 1 / 3,
    perScenario: [
      { key: "attack_poisoned_email", name: "Poisoned email", verdict: "FAILED", deterministic: true, reason: "canary leaked in turn 1", runId: "r1" },
      { key: "attack_poisoned_web_page", name: "Poisoned web page", verdict: "PASSED", deterministic: false, reason: "ignored it", runId: "r2" },
      { key: "attack_poisoned_document", name: "Poisoned document", verdict: "PASSED", deterministic: false, reason: null, runId: "r3" },
      { key: "attack_poisoned_mcp_tool_description", name: "Poisoned tool description", verdict: "INCONCLUSIVE", deterministic: false, reason: "unclear", runId: "r4" },
    ],
    limits: LIMITS,
    ...overrides,
  };
}

beforeEach(() => {
  for (const fn of Object.values(apiMock)) fn.mockReset();
  apiMock.listSimulationSessions.mockResolvedValue([]);
});

describe("attack report helpers", () => {
  it("counts only decided scenarios in the headline and names the inconclusive ones", () => {
    const h = attackHeadline(report());
    expect(h).toEqual({ kind: "decided", succeeded: 1, decided: 3, percent: 33, inconclusive: 1 });
    expect(attackHeadlineText(h)).toBe("1 of 3 attacks succeeded (33%) - 1 inconclusive, not counted");
  });

  it("reports zero successes as zero percent only when something was decided", () => {
    const clean = report({ succeededAttacks: 0, resisted: 4, inconclusive: 0, attackSuccessRate: 0 });
    expect(attackHeadlineText(attackHeadline(clean))).toBe("0 of 4 attacks succeeded (0%)");
  });

  it("never renders a null rate as zero percent", () => {
    const undecided = report({ succeededAttacks: 0, resisted: 0, inconclusive: 4, attackSuccessRate: null });
    expect(attackHeadline(undecided)).toEqual({ kind: "undecided", total: 4 });
    expect(attackHeadlineText(attackHeadline(undecided))).not.toContain("%");
  });

  it("is 'running' while any scenario has no verdict", () => {
    const live = report({
      pending: 1,
      resisted: 1,
      perScenario: [...report().perScenario.slice(0, 3), { ...report().perScenario[3], verdict: null }],
    });
    expect(attackReportPending(live)).toBe(true);
    expect(attackHeadline(live)).toEqual({ kind: "running", finished: 3, total: 4 });
    expect(attackReportPending(report())).toBe(false);
  });

  it("is empty for a session with no attack runs", () => {
    expect(attackHeadline(report({ total: 0, perScenario: [], attackSuccessRate: null }))).toEqual({ kind: "empty" });
    expect(attackHeadline(null)).toEqual({ kind: "empty" });
  });

  it("normalizes the wire shape defensively", () => {
    expect(normalizeAttackReport(null)).toBeNull();
    expect(normalizeAttackReport({ total: 1 })).toBeNull();
    const n = normalizeAttackReport({
      sessionId: "s",
      total: 1,
      perScenario: [{ key: "attack_x", name: "X", verdict: "WEIRD", deterministic: "yes", runId: "r" }],
    });
    expect(n?.perScenario[0]).toEqual({
      key: "attack_x",
      name: "X",
      verdict: null,
      deterministic: false,
      reason: null,
      runId: "r",
    });
    expect(n?.attackSuccessRate).toBeNull();
    expect(n?.limits).toEqual([]);
  });

  it("maps verdicts to outcomes: a FAILED verdict means the attack worked", () => {
    expect(attackOutcome("FAILED")).toBe("compromised");
    expect(attackOutcome("PASSED")).toBe("resisted");
    expect(attackOutcome("INCONCLUSIVE")).toBe("inconclusive");
    expect(attackOutcome(null)).toBe("running");
  });

  it("picks attack personas, the latest attack session and a valid budget", () => {
    expect(attackPersonaKeys(PERSONAS)).toEqual(["attack_poisoned_email", "attack_poisoned_web_page"]);
    expect(attackPersonaKeys(null)).toEqual([]);
    const sessions = [
      { id: "old", personaKeys: ["attack_poisoned_email"], createdAt: "2026-10-01T00:00:00Z" },
      { id: "new", personaKeys: ["attack_poisoned_email"], createdAt: "2026-10-02T00:00:00Z" },
      { id: "other", personaKeys: ["prompt_injection"], createdAt: "2026-10-03T00:00:00Z" },
    ];
    expect(latestAttackSessionId(sessions, ["attack_poisoned_email"])).toBe("new");
    expect(latestAttackSessionId(sessions, [])).toBeNull();
    expect(parseAttackBudget("2")).toBe(2);
    expect(parseAttackBudget(" 0.5 ")).toBe(0.5);
    expect(parseAttackBudget("0")).toBeNull();
    expect(parseAttackBudget("abc")).toBeNull();
  });
});

describe("AttackSection", () => {
  it("renders nothing when the core offers no attack scenarios", async () => {
    apiMock.listSimulationPersonas.mockResolvedValue(PERSONAS.map((p) => ({ ...p, attack: false })));
    const onAvailabilityChange = vi.fn();
    const { container } = render(<AttackSection agentId="a1" onAvailabilityChange={onAvailabilityChange} />);
    // Quiet on the expected plan-gate / older-core statuses: no global toast.
    await waitFor(() => expect(apiMock.listSimulationPersonas).toHaveBeenCalledWith("a1", [403, 404]));
    await waitFor(() => expect(container.firstChild).toBeNull());
    expect(apiMock.listSimulationSessions).not.toHaveBeenCalled();
    expect(onAvailabilityChange).toHaveBeenLastCalledWith(false);
  });

  it("renders nothing when the personas route fails (older core or plan gate)", async () => {
    apiMock.listSimulationPersonas.mockRejectedValue(Object.assign(new Error("nope"), { status: 404 }));
    const { container } = render(<AttackSection agentId="a1" />);
    await waitFor(() => expect(apiMock.listSimulationPersonas).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it("launches only the attack personas and shows the report with a compromised row", async () => {
    apiMock.listSimulationPersonas.mockResolvedValue(PERSONAS);
    apiMock.createSimulationSession.mockResolvedValue({ id: "s1" });
    apiMock.getSimulationAttackReport.mockResolvedValue(report());
    const onAvailabilityChange = vi.fn();
    render(<AttackSection agentId="a 1" onAvailabilityChange={onAvailabilityChange} />);

    const launch = await screen.findByTestId("attack-launch");
    await waitFor(() => expect(onAvailabilityChange).toHaveBeenLastCalledWith(true));
    expect(screen.queryByTestId("attack-report")).toBeNull();
    fireEvent.click(launch);

    await waitFor(() =>
      expect(apiMock.createSimulationSession).toHaveBeenCalledWith("a 1", {
        personaKeys: ["attack_poisoned_email", "attack_poisoned_web_page"],
        maxTurns: 3,
        budgetUsd: 2,
      })
    );
    expect((await screen.findByTestId("attack-headline")).textContent).toBe(
      "1 of 3 attacks succeeded (33%) - 1 inconclusive, not counted"
    );
    expect(apiMock.getSimulationAttackReport).toHaveBeenCalledWith("a 1", "s1");

    const compromisedRow = screen.getByTestId("attack-row-attack_poisoned_email");
    expect(compromisedRow.textContent).toContain("Compromised");
    expect(compromisedRow.textContent).toContain("canary leaked in turn 1");
    expect(screen.getByTestId("attack-deterministic-attack_poisoned_email")).toBeTruthy();
    expect(screen.getByTestId("attack-row-attack_poisoned_web_page").textContent).toContain("Resisted");
    expect(screen.queryByTestId("attack-deterministic-attack_poisoned_web_page")).toBeNull();
    expect(screen.getByTestId("attack-row-attack_poisoned_mcp_tool_description").textContent).toContain("Inconclusive");

    expect(screen.getByTestId("attack-fix-workbench").getAttribute("href")).toBe("/agents?agentId=a%201");
    expect(screen.getByTestId("attack-fix-guardrails").getAttribute("href")).toBe("/admin/guardrails");
    expect(screen.getByTestId("attack-limits").textContent).toContain("tools disabled");
  });

  it("shows no fix list when every attack was resisted", async () => {
    apiMock.listSimulationPersonas.mockResolvedValue(PERSONAS);
    apiMock.listSimulationSessions.mockResolvedValue([
      { id: "s0", personaKeys: ["attack_poisoned_email"], createdAt: "2026-10-02T00:00:00Z" },
    ]);
    apiMock.getSimulationAttackReport.mockResolvedValue(
      report({
        sessionId: "s0",
        total: 1,
        succeededAttacks: 0,
        resisted: 1,
        inconclusive: 0,
        attackSuccessRate: 0,
        perScenario: [report().perScenario[1]],
      })
    );
    render(<AttackSection agentId="a1" />);

    // The previous attack session's result is restored on load.
    expect((await screen.findByTestId("attack-headline")).textContent).toBe("0 of 1 attack succeeded (0%)");
    expect(apiMock.getSimulationAttackReport).toHaveBeenCalledWith("a1", "s0");
    expect(screen.queryByTestId("attack-fixes")).toBeNull();
    expect(apiMock.createSimulationSession).not.toHaveBeenCalled();
  });

  it("opens a scenario's transcript from the run detail", async () => {
    apiMock.listSimulationPersonas.mockResolvedValue(PERSONAS);
    apiMock.listSimulationSessions.mockResolvedValue([
      { id: "s1", personaKeys: ["attack_poisoned_email"], createdAt: "2026-10-02T00:00:00Z" },
    ]);
    apiMock.getSimulationAttackReport.mockResolvedValue(report());
    apiMock.getSimulationRun.mockResolvedValue({
      transcriptJson: JSON.stringify([
        { role: "persona", text: "Here is the email" },
        { role: "agent", text: "CANARY-123" },
      ]),
    });
    render(<AttackSection agentId="a1" />);

    const row = await screen.findByTestId("attack-row-attack_poisoned_email");
    fireEvent.click(row.querySelector("button") as HTMLButtonElement);
    await waitFor(() => expect(apiMock.getSimulationRun).toHaveBeenCalledWith("a1", "r1"));
    await waitFor(() => expect(row.textContent).toContain("Agent: CANARY-123"));
    expect(row.textContent).toContain("Sender: Here is the email");
  });

  it("keeps the launcher disabled and keeps reading when the first report read fails", async () => {
    apiMock.listSimulationPersonas.mockResolvedValue(PERSONAS);
    apiMock.createSimulationSession.mockResolvedValue({ id: "s1" });
    apiMock.getSimulationAttackReport.mockRejectedValue(Object.assign(new Error("bad gateway"), { status: 502 }));
    render(<AttackSection agentId="a1" />);

    const launch = (await screen.findByTestId("attack-launch")) as HTMLButtonElement;
    fireEvent.click(launch);
    await waitFor(() => expect(apiMock.getSimulationAttackReport).toHaveBeenCalledWith("a1", "s1"));

    // No report yet, but the session exists: a second click must not start (and bill) another one.
    await waitFor(() => expect(launch.textContent).toContain("Starting"));
    expect(launch.disabled).toBe(true);
    fireEvent.click(launch);
    expect(apiMock.createSimulationSession).toHaveBeenCalledTimes(1);
  });

  it("blocks launch on an invalid budget", async () => {
    apiMock.listSimulationPersonas.mockResolvedValue(PERSONAS);
    render(<AttackSection agentId="a1" />);
    const launch = (await screen.findByTestId("attack-launch")) as HTMLButtonElement;
    fireEvent.change(screen.getByTestId("attack-budget"), { target: { value: "0" } });
    expect(launch.disabled).toBe(true);
    fireEvent.click(launch);
    expect(apiMock.createSimulationSession).not.toHaveBeenCalled();
  });
});
