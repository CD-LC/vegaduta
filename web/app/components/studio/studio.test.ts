import { describe, expect, it } from "vitest";
import { foldTrace } from "./TraceView";
import { diffSpec, draftFromAgent, type DesignerDraft } from "./AgentDesigner";
import { chainFromAgents, parseConnectorTool, toolLabel } from "./studioContent";
import { EMPTY_BRIEF, briefReady, draftFromBrief, draftFromTopic, nameFromTopic, suggestCare, suggestNeeds } from "./topicDraft";
import type { StudioAgent, StudioEvent } from "./studioClient";

const agent: StudioAgent = {
  id: "a1",
  name: "Scout",
  mission: "Find things",
  reasoningDepth: "CAREFUL",
  autonomy: "SEMI_AUTONOMOUS",
  tools: ["web_search", "fetch_web_page"],
  toolsOff: false,
  guardrails: ["profanity"],
  temperature: 0.4,
  maxTokens: 1024,
  mine: true,
  live: false,
  createdBySession: "s1",
  createdAt: "2026-09-25T00:00:00Z",
  updatedAt: "2026-09-25T00:00:00Z",
};

describe("foldTrace", () => {
  it("pairs a tool_call with its tool_result and keeps plan/guardrail rows in order", () => {
    const events: StudioEvent[] = [
      { type: "plan", text: "1. search 2. read" },
      { type: "tool_call", id: 1, tool: "web_search", args: '{"query":"rbi upi"}' },
      { type: "tool_result", id: 1, tool: "web_search", preview: "3 results", ms: 812 },
      { type: "guardrail", tool: "fetch_web_page", reason: "blocked host" },
      { type: "run_finished", ms: 2400 },
    ];
    const rows = foldTrace(events);
    expect(rows.map((r) => r.kind)).toEqual(["plan", "tool", "guardrail", "finished"]);
    const tool = rows[1];
    expect(tool.kind === "tool" && tool.preview).toBe("3 results");
    expect(tool.kind === "tool" && tool.ms).toBe(812);
  });

  it("attaches a tool error to its call instead of adding a loose error row", () => {
    const rows = foldTrace([
      { type: "tool_call", id: 7, tool: "http_request", args: "{}" },
      { type: "error", id: 7, tool: "http_request", message: "timeout", ms: 30000 },
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].kind === "tool" && rows[0].error).toBe("timeout");
  });

  it("keeps a workflow-level run_finished (with status) out of the mission trace", () => {
    const rows = foldTrace([{ type: "run_finished", ms: 10, status: "COMPLETED", output: "x" }]);
    expect(rows).toHaveLength(0);
  });
});

describe("diffSpec", () => {
  it("sends only the fields that changed", () => {
    const base = draftFromAgent(agent);
    const draft: DesignerDraft = { ...base, reasoningDepth: "RIGOROUS", tools: ["fetch_web_page", "web_search"] };
    expect(diffSpec(base, draft)).toEqual({ reasoningDepth: "RIGOROUS" });
  });

  it("sends an explicit empty tool list for chatbot mode", () => {
    const base = draftFromAgent(agent);
    expect(diffSpec(base, { ...base, tools: [] })).toEqual({ tools: [] });
  });

  it("sends everything, including the name, for a brand-new agent", () => {
    const draft = draftFromAgent(agent);
    const spec = diffSpec(null, draft);
    expect(spec.name).toBe("Scout");
    expect(spec.mission).toBe("Find things");
    expect(spec.tools).toEqual(["web_search", "fetch_web_page"]);
  });
});

describe("presets and templates", () => {
  it("chains the visitor's OWN agents, never a neighbour's and never a canned pattern", () => {
    const chain = chainFromAgents([
      { id: "n1", name: "Customer assistant", mine: false },
      { id: "m1", name: "Invoice chaser", mine: true },
      { id: "m2", name: "Reminder writer", mine: true },
    ]);
    expect(chain).not.toBeNull();
    const steps = chain!.steps;
    expect(steps[0].type).toBe("trigger");
    expect(steps.filter((x) => x.type === "agent").map((x) => x.config.agentId)).toEqual(["m1", "m2"]);
    expect(steps.some((x) => x.type === "approval")).toBe(true);
    expect(steps[steps.length - 1].type).toBe("output");
    expect(steps.length).toBeLessThanOrEqual(8);
    expect(chain!.name).toContain("Invoice chaser");
    expect(JSON.stringify(steps)).not.toContain("Customer assistant");
  });
  it("gives one agent a one-agent chain with a sign-off, and nothing when they have no agent", () => {
    const one = chainFromAgents([{ id: "m1", name: "Rent explainer", mine: true }])!;
    expect(one.steps.map((x) => x.type)).toEqual(["trigger", "agent", "approval", "output"]);
    expect(chainFromAgents([{ id: "n1", name: "Someone else's", mine: false }])).toBeNull();
    expect(chainFromAgents([])).toBeNull();
  });
  it("never chains more than three agents", () => {
    const many = Array.from({ length: 6 }, (_, i) => ({ id: `m${i}`, name: `Agent ${i}`, mine: true }));
    expect(chainFromAgents(many)!.steps.filter((x) => x.type === "agent")).toHaveLength(3);
  });
});

describe("topic drafting (any topic, nothing tied to a network)", () => {
  it("builds a usable draft from a topic nobody anticipated", () => {
    const d = draftFromTopic("beekeeping in Coorg - hive inspections and honey pricing");
    expect(d.spec.name.length).toBeGreaterThan(0);
    expect(d.spec.name.length).toBeLessThanOrEqual(60);
    expect(d.spec.mission).toContain("beekeeping in Coorg");
    expect(d.spec.tools).toEqual(expect.arrayContaining(["remember_fact", "recall_related_facts"]));
    expect(d.sampleMissions).toHaveLength(3);
  });
  it("arms a tax topic with code and marks it high-stakes", () => {
    const d = draftFromTopic("explaining income tax for freelancers");
    expect(d.spec.tools).toContain("run_code");
    expect(d.spec.reasoningDepth).toBe("RIGOROUS");
    expect(d.spec.guardrails).toContain("pii");
  });
  it("gives document-shaped topics the knowledge tool", () => {
    expect(draftFromTopic("summarising rental agreements").spec.tools).toContain("search_knowledge_base");
  });
  it("names agents within the server limit even for a long rambling topic", () => {
    expect(nameFromTopic("x".repeat(400)).length).toBeLessThanOrEqual(60);
  });
  it("composes the agent from the visitor's own answers - nothing pre-built", () => {
    const d = draftFromBrief({
      ...EMPTY_BRIEF,
      job: "answer hive questions for hobby beekeepers in Coorg",
      audience: "first-time beekeepers on WhatsApp",
      never: "recommend chemical treatments",
      care: "RIGOROUS",
      autonomy: "FULLY_AUTONOMOUS",
      creativity: 0,
      needs: { web: true, code: false, api: false, docs: true },
    });
    expect(d.spec.mission).toContain("hive questions");
    expect(d.spec.mission).toContain("first-time beekeepers on WhatsApp");
    expect(d.spec.mission).toContain("NEVER: recommend chemical treatments");
    expect(d.spec.reasoningDepth).toBe("RIGOROUS");
    expect(d.spec.autonomy).toBe("FULLY_AUTONOMOUS");
    expect(d.spec.temperature).toBe(0);
    expect(d.spec.tools).toEqual(expect.arrayContaining(["web_search", "search_knowledge_base"]));
    expect(d.spec.tools).not.toContain("run_code");
  });
  it("only gives the hands the visitor ticked", () => {
    const d = draftFromBrief({ ...EMPTY_BRIEF, job: "write short stories about lighthouses", needs: { web: false, code: false, api: false, docs: false } });
    expect(d.spec.tools.sort()).toEqual(["recall_related_facts", "remember_fact"]);
  });
  it("suggestions are only suggestions and never make the brief ready by themselves", () => {
    expect(briefReady(EMPTY_BRIEF)).toBe(false);
    expect(suggestNeeds("summarising rental agreements").docs).toBe(true);
    expect(suggestCare("explaining income tax")).toBe("RIGOROUS");
    expect(suggestCare("tell jokes")).toBe("FAST");
  });
});

describe("connector tool names", () => {
  it("reads a studio connector tool back into words", () => {
    expect(parseConnectorTool("mcp__demo-1a2b3c4d-github__list_repos")).toEqual({ server: "github", tool: "list repos" });
    expect(parseConnectorTool("web_search")).toBeNull();
    expect(toolLabel("mcp__demo-1a2b3c4d-github__list_repos")).toBe("github: list repos");
  });
});
