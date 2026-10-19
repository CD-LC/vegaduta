import { describe, expect, it } from "vitest";
import { buildJobGoal, dispatch, needsBackground, parseSchedule, parseSlash, scheduleName } from "./dispatch";

const ALL_ON = { research: true, scheduledTasks: true, jobs: true };
const ALL_OFF = { research: false, scheduledTasks: false, jobs: false };

describe("dispatch", () => {
  it("sends ordinary text, including unknown slashes, as one turn", () => {
    expect(dispatch("book me a dentist", ALL_ON)).toEqual({ rung: "turn", message: "book me a dentist" });
    expect(dispatch("/frobnicate now", ALL_ON)).toEqual({ rung: "turn", message: "/frobnicate now" });
    expect(dispatch("   ", ALL_ON)).toEqual({ rung: "empty" });
  });

  it("routes /research to a research run only when runs are on", () => {
    expect(dispatch("/research best e-bikes 2026", ALL_ON)).toEqual({ rung: "research", question: "best e-bikes 2026" });
    const degraded = dispatch("/research best e-bikes 2026", ALL_OFF);
    expect(degraded.rung).toBe("turn");
    if (degraded.rung === "turn") {
      expect(degraded.degradedFrom).toBe("research");
      expect(degraded.message).toContain("best e-bikes 2026");
    }
    expect(dispatch("/research", ALL_ON)).toEqual({ rung: "command", command: "help", arg: "research" });
  });

  it("routes /schedule to a routine when the cadence parses and the feature is on", () => {
    const plan = dispatch("/schedule every weekday at 8 summarise my inbox", ALL_ON);
    expect(plan.rung).toBe("schedule");
    if (plan.rung === "schedule") {
      expect(plan.spec).toMatchObject({ frequency: "weekdays", time: "08:00", prompt: "summarise my inbox" });
    }
    // Off: the agent gets the request (it has schedule_task).
    expect(dispatch("/schedule every weekday at 8 summarise my inbox", { ...ALL_ON, scheduledTasks: false })).toMatchObject({
      rung: "turn",
      degradedFrom: "schedule",
    });
    // Unparseable cadence: also the agent's problem, not a form.
    expect(dispatch("/schedule whenever it rains water the plants", ALL_ON)).toMatchObject({
      rung: "turn",
      degradedFrom: "schedule",
    });
  });

  it("routes /background and the background flag to a job, degrading when jobs are unreachable", () => {
    expect(dispatch("/background rename all my files", ALL_ON)).toEqual({ rung: "job", goal: "rename all my files" });
    expect(dispatch("rename all my files", ALL_ON, { background: true })).toEqual({ rung: "job", goal: "rename all my files" });
    expect(dispatch("rename all my files", ALL_OFF, { background: true })).toEqual({
      rung: "turn",
      message: "rename all my files",
      degradedFrom: "background",
    });
  });

  it("routes /agent <name> <message> to a specialist, quoting multi-word names", () => {
    expect(dispatch('/agent "Invoice chaser" send the June reminders', ALL_ON)).toEqual({
      rung: "specialist",
      agentName: "Invoice chaser",
      message: "send the June reminders",
    });
    expect(dispatch("/agent Researcher what is new", ALL_ON)).toEqual({
      rung: "specialist",
      agentName: "Researcher",
      message: "what is new",
    });
    expect(dispatch("/agent Researcher", ALL_ON)).toEqual({ rung: "command", command: "help", arg: "agent" });
  });

  it("maps the local commands", () => {
    expect(dispatch("/new", ALL_ON)).toEqual({ rung: "command", command: "new", arg: "" });
    expect(dispatch("/export", ALL_ON)).toEqual({ rung: "command", command: "export", arg: "" });
    expect(dispatch("/help", ALL_ON)).toEqual({ rung: "command", command: "help", arg: "" });
    expect(dispatch("/remember I prefer mornings", ALL_ON)).toEqual({
      rung: "command",
      command: "remember",
      arg: "I prefer mornings",
    });
  });
});

describe("parseSlash", () => {
  it("reads the command and the argument", () => {
    expect(parseSlash("/Research   x  y ")).toEqual({ command: "research", arg: "x  y" });
    expect(parseSlash("/new")).toEqual({ command: "new", arg: "" });
    expect(parseSlash("not a command")).toBeNull();
    expect(parseSlash("/ spaced")).toBeNull();
  });
});

describe("parseSchedule", () => {
  it("understands the cadence grammar", () => {
    expect(parseSchedule("every day at 7:30 check the weather")).toMatchObject({
      frequency: "daily",
      time: "07:30",
      prompt: "check the weather",
    });
    expect(parseSchedule("daily check the weather")).toMatchObject({ frequency: "daily", time: "09:00" });
    expect(parseSchedule("every monday at 8pm: plan the week")).toMatchObject({
      frequency: "weekly",
      day: "MON",
      time: "20:00",
      prompt: "plan the week",
    });
    expect(parseSchedule("weekly send the report")).toMatchObject({ frequency: "weekly", day: "MON" });
    expect(parseSchedule("monthly on the 15th at 12am pay rent")).toMatchObject({
      frequency: "monthly",
      day: "15",
      time: "00:00",
    });
    expect(parseSchedule("monthly on the last reconcile")).toMatchObject({ frequency: "monthly", day: "LAST" });
    expect(parseSchedule("monthly reconcile")).toMatchObject({ frequency: "monthly", day: "1" });
  });

  it("refuses what the server would refuse", () => {
    expect(parseSchedule("every day at 25 do it")).toBeNull();
    expect(parseSchedule("monthly on the 31st do it")).toBeNull();
    expect(parseSchedule("every day at 8")).toBeNull();
    expect(parseSchedule("sometimes do it")).toBeNull();
  });

  it("names the routine from the prompt", () => {
    expect(scheduleName("summarise my inbox")).toBe("summarise my inbox");
    expect(scheduleName("a".repeat(80))).toHaveLength(61);
    const named = scheduleName("send the weekly sales report to the regional managers and the finance team please");
    expect(named.endsWith("…")).toBe(true);
    expect(named.length).toBeLessThanOrEqual(61);
  });
});

describe("needsBackground", () => {
  it("is true only for the tool-step cap", () => {
    expect(needsBackground(null)).toBe(false);
    expect(needsBackground([{ type: "tool_call", id: 1, tool: "web_search" }])).toBe(false);
    expect(
      needsBackground([{ type: "guardrail", id: 2, message: "exceeded the maximum of 15 tool calls for a single turn" }])
    ).toBe(true);
    expect(needsBackground([{ type: "guardrail", id: 3, message: "run_budget_exceeded" }])).toBe(false);
  });
});

describe("buildJobGoal", () => {
  it("is just the request for an empty thread", () => {
    expect(buildJobGoal([], "  finish it ")).toBe("finish it");
  });

  it("keeps the newest exchanges that fit under the cap", () => {
    const thread = [
      { role: "user" as const, content: "old " + "x".repeat(100) },
      { role: "assistant" as const, content: "reply " + "y".repeat(100) },
      { role: "user" as const, content: "latest question" },
    ];
    const goal = buildJobGoal(thread, "keep going", 260);
    expect(goal.length).toBeLessThanOrEqual(260);
    expect(goal).toContain("User: latest question");
    expect(goal).toContain("Request:\nkeep going");
    expect(goal).not.toContain("old xxx");
    const full = buildJobGoal(thread, "keep going");
    expect(full).toContain("old xxx");
    expect(full.indexOf("old xxx")).toBeLessThan(full.indexOf("latest question"));
  });
});
