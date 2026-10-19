import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  ASSISTANT_MARKER_LABEL,
  ASSISTANT_PROMPT_V1,
  ASSISTANT_PROMPT_V1_SHELL,
  ASSISTANT_PROMPT_V2,
  ASSISTANT_PROMPT_V5,
  ASSISTANT_PROMPT_V6,
  ASSISTANT_SYSTEM_PROMPT,
  buildMarker,
  ensureAssistantAgent,
  markerAgentId,
  pickAssistantAgent,
  selectAssistantTools,
  syncAssistantTools,
  type AssistantAgentApi,
} from "./assistantAgent";

const agent = (id: string, name: string, extra: Record<string, unknown> = {}) =>
  ({ id, name, knowledgeBaseEnabled: false, ...extra }) as unknown as Parameters<typeof pickAssistantAgent>[0][number];

function mockApi(overrides: Partial<AssistantAgentApi> = {}): AssistantAgentApi {
  return {
    listAgents: vi.fn().mockResolvedValue([]),
    getMemoryQuiet: vi.fn().mockResolvedValue(null),
    getDefaultAgentModel: vi
      .fn()
      .mockResolvedValue({ providerName: "OLLAMA", model: "qwen3.5:4b", source: "PLATFORM_POOL" }),
    createAgent: vi
      .fn()
      .mockImplementation(async (payload: Record<string, unknown>) => agent("new-1", String(payload.name))),
    listAvailableTools: vi
      .fn()
      .mockResolvedValue([
        { name: "web_search" },
        { name: "http_request_write" },
        { name: "razorpay_create_payment_link" },
        { name: "remember_fact" },
      ]),
    setAgentToolAccess: vi.fn().mockResolvedValue({}),
    setAgentKnowledgeBase: vi.fn().mockResolvedValue({}),
    saveMemoryBlock: vi.fn().mockResolvedValue({}),
    updateAgentPrompt: vi.fn().mockResolvedValue({}),
    ...overrides,
  };
}

beforeEach(() => vi.clearAllMocks());

describe("selectAssistantTools", () => {
  it("drops payments and http_request_write, trims and de-duplicates", () => {
    expect(
      selectAssistantTools([
        { name: " web_search " },
        { name: "web_search" },
        { name: "http_request_write" },
        { name: "http_request" },
        { name: "payments_charge" },
        { name: "Stripe_refund" },
        { name: "" },
      ])
    ).toEqual(["web_search", "http_request"]);
    expect(selectAssistantTools(null)).toEqual([]);
  });
});

describe("marker block", () => {
  it("round-trips the agent id through the project note", () => {
    const content = buildMarker("agent-42", { name: "Priya", locale: "en-IN", timeZone: "Asia/Kolkata" });
    expect(content.split("\n")).toEqual([
      "Personal assistant agent id: agent-42",
      "Name: Priya",
      "Locale: en-IN",
      "Time zone: Asia/Kolkata",
    ]);
    expect(markerAgentId({ blocks: [{ label: ASSISTANT_MARKER_LABEL, content }] })).toBe("agent-42");
    expect(markerAgentId({ blocks: [{ label: "persona", content }] })).toBeNull();
    expect(markerAgentId(null)).toBeNull();
    expect(ASSISTANT_MARKER_LABEL).toMatch(/^project:[a-z0-9][a-z0-9-]{2,39}$/);
  });
});

describe("pickAssistantAgent", () => {
  it("prefers the marked agent, then one named Assistant", () => {
    const agents = [agent("a", "Support"), agent("b", " assistant "), agent("c", "Assistant")];
    expect(pickAssistantAgent(agents, "c")?.id).toBe("c");
    expect(pickAssistantAgent(agents, "gone")?.id).toBe("b");
    expect(pickAssistantAgent(agents, null)?.id).toBe("b");
    expect(pickAssistantAgent([agent("a", "Support")], null)).toBeNull();
  });

  it("never adopts another user's shared agent by name on a shared install", () => {
    // createdBy is the creator's auth subject; a colleague's agent (EDITOR grant included) does not match.
    const theirs = agent("theirs", "Assistant", { createdBy: "teammate-sub" });
    const mine = agent("mine", "Assistant", { createdBy: "my-sub" });
    const legacy = agent("old", "Assistant", { createdBy: null });
    expect(pickAssistantAgent([theirs], null, "my-sub")).toBeNull();
    expect(pickAssistantAgent([theirs, mine], null, "my-sub")?.id).toBe("mine");
    // A pre-V151 row has no creator recorded - anyone's guess, so it is fair to adopt.
    expect(pickAssistantAgent([legacy], null, "my-sub")?.id).toBe("old");
    // The marker still wins even when it names a teammate's row (the user wrote it).
    expect(pickAssistantAgent([theirs, mine], "theirs", "my-sub")?.id).toBe("theirs");
  });
});

describe("ensureAssistantAgent", () => {
  it("returns the marked agent without creating anything", async () => {
    const api = mockApi({
      listAgents: vi.fn().mockResolvedValue([agent("x", "Other"), agent("mine", "My helper")]),
      getMemoryQuiet: vi
        .fn()
        .mockResolvedValue({ blocks: [{ label: ASSISTANT_MARKER_LABEL, content: buildMarker("mine") }] }),
    });
    const result = await ensureAssistantAgent(api);
    expect(result).toMatchObject({ status: "ready", created: false });
    if (result.status === "ready") expect(result.agent.id).toBe("mine");
    expect(api.createAgent).not.toHaveBeenCalled();
    expect(api.saveMemoryBlock).not.toHaveBeenCalled();
  });

  it("adopts an agent named Assistant and writes the marker for next time", async () => {
    const api = mockApi({ listAgents: vi.fn().mockResolvedValue([agent("named", "Assistant")]) });
    const result = await ensureAssistantAgent(api, { name: "Priya" });
    expect(result).toMatchObject({ status: "ready", created: false });
    expect(api.createAgent).not.toHaveBeenCalled();
    expect(api.saveMemoryBlock).toHaveBeenCalledWith(
      ASSISTANT_MARKER_LABEL,
      expect.stringContaining("agent id: named")
    );
  });

  it("switches the knowledge base back on for an existing Assistant that has it off", async () => {
    const off = mockApi({ listAgents: vi.fn().mockResolvedValue([agent("named", "Assistant", { knowledgeBaseEnabled: false })]) });
    await ensureAssistantAgent(off, {});
    expect(off.setAgentKnowledgeBase).toHaveBeenCalledWith("named", true);
    const on = mockApi({ listAgents: vi.fn().mockResolvedValue([agent("named", "Assistant", { knowledgeBaseEnabled: true })]) });
    await ensureAssistantAgent(on, {});
    expect(on.setAgentKnowledgeBase).not.toHaveBeenCalled();
  });

  it("creates the agent with the default model, then grants tools, knowledge and the marker", async () => {
    const api = mockApi();
    const result = await ensureAssistantAgent(api, { locale: "en-GB" });
    expect(result).toMatchObject({ status: "ready", created: true });
    expect(api.createAgent).toHaveBeenCalledWith({
      name: "Assistant",
      systemPrompt: ASSISTANT_SYSTEM_PROMPT,
      providerName: "OLLAMA",
      model: "qwen3.5:4b",
      experienceMode: "SIMPLE",
      knowledgeBaseEnabled: true,
    });
    expect(api.setAgentToolAccess).toHaveBeenCalledWith("new-1", ["web_search", "remember_fact"]);
    expect(api.setAgentKnowledgeBase).toHaveBeenCalledWith("new-1", true);
    expect(api.saveMemoryBlock).toHaveBeenCalledWith(
      ASSISTANT_MARKER_LABEL,
      "Personal assistant agent id: new-1\nLocale: en-GB"
    );
  });

  it("still returns a usable agent when the follow-up PATCHes fail", async () => {
    const api = mockApi({
      listAvailableTools: vi.fn().mockRejectedValue(new Error("mcp slow")),
      setAgentKnowledgeBase: vi.fn().mockRejectedValue(new Error("locked")),
      saveMemoryBlock: vi.fn().mockRejectedValue(new Error("memory off")),
    });
    await expect(ensureAssistantAgent(api)).resolves.toMatchObject({ status: "ready", created: true });
    expect(api.setAgentToolAccess).not.toHaveBeenCalled();
  });

  it("reports no-model instead of creating an agent with no provider", async () => {
    const api = mockApi({
      getDefaultAgentModel: vi
        .fn()
        .mockResolvedValue({ providerName: null, model: null, source: "NONE", message: "Add a provider first." }),
    });
    await expect(ensureAssistantAgent(api)).resolves.toEqual({ status: "no-model", message: "Add a provider first." });
    expect(api.createAgent).not.toHaveBeenCalled();
    const quiet = mockApi({ getDefaultAgentModel: vi.fn().mockResolvedValue(null) });
    await expect(ensureAssistantAgent(quiet)).resolves.toEqual({ status: "no-model", message: null });
  });
});

describe("prompt upgrade", () => {
  it("moves an agent still on the first prompt to the current one", async () => {
    const api = mockApi({
      listAgents: vi.fn().mockResolvedValue([agent("a-1", "Assistant", { systemPrompt: ASSISTANT_PROMPT_V1 })]),
    });
    await ensureAssistantAgent(api);
    expect(api.updateAgentPrompt).toHaveBeenCalledWith("a-1", ASSISTANT_SYSTEM_PROMPT);
  });
  it("moves every earlier shipped prompt to the current one, which knows the build tools", async () => {
    for (const old of [ASSISTANT_PROMPT_V1_SHELL, ASSISTANT_PROMPT_V2, ASSISTANT_PROMPT_V5, ASSISTANT_PROMPT_V6]) {
      const api = mockApi({
        listAgents: vi.fn().mockResolvedValue([agent("a-1", "Assistant", { systemPrompt: old })]),
      });
      await ensureAssistantAgent(api);
      expect(api.updateAgentPrompt).toHaveBeenCalledWith("a-1", ASSISTANT_SYSTEM_PROMPT);
    }
    expect(ASSISTANT_SYSTEM_PROMPT).toContain("workspace_write");
    expect(ASSISTANT_SYSTEM_PROMPT).toContain("local_shell");
    expect(ASSISTANT_SYSTEM_PROMPT).toContain("run_subtasks");
    expect(ASSISTANT_SYSTEM_PROMPT).toContain("delegate_parallel");
    expect(ASSISTANT_SYSTEM_PROMPT).toContain("create_workflow");
    expect(ASSISTANT_SYSTEM_PROMPT).toContain("/workflows/<id>");
  });
  it("leaves a prompt the person edited alone", async () => {
    const api = mockApi({
      listAgents: vi.fn().mockResolvedValue([agent("a-1", "Assistant", { systemPrompt: "Be terse." })]),
    });
    await ensureAssistantAgent(api);
    expect(api.updateAgentPrompt).not.toHaveBeenCalled();
  });
});

describe("syncAssistantTools", () => {
  const listed = [
    { name: "web_search" },
    { name: "google_calendar" },
    { name: "channel_send_message" },
    { name: "local_shell" },
    { name: "local_shell_read" },
    { name: "run_subtasks" },
    { name: "create_workflow" },
    { name: "payments_charge" },
  ];
  it("adds the tools of newly connected services and nothing else", async () => {
    const api = mockApi({
      listAgents: vi.fn().mockResolvedValue([agent("a-1", "Assistant", { allowedToolNames: ["web_search"] })]),
      listAvailableTools: vi.fn().mockResolvedValue(listed),
    });
    expect(await syncAssistantTools(api, "a-1")).toBe(true);
    expect(api.setAgentToolAccess).toHaveBeenCalledWith("a-1", [
      "web_search",
      "google_calendar",
      "channel_send_message",
      "local_shell",
      "local_shell_read",
      "run_subtasks",
      "create_workflow",
    ]);
  });
  it("does nothing when the agent is unrestricted or already has them", async () => {
    const open = mockApi({
      listAgents: vi.fn().mockResolvedValue([agent("a-1", "Assistant", { allowedToolNames: [] })]),
    });
    expect(await syncAssistantTools(open, "a-1")).toBe(false);
    const full = mockApi({
      listAgents: vi.fn().mockResolvedValue([
        agent("a-1", "Assistant", {
          allowedToolNames: [
            "web_search",
            "google_calendar",
            "channel_send_message",
            "local_shell",
            "local_shell_read",
            "run_subtasks",
            "create_workflow",
          ],
        }),
      ]),
      listAvailableTools: vi.fn().mockResolvedValue(listed),
    });
    expect(await syncAssistantTools(full, "a-1")).toBe(false);
    expect(full.setAgentToolAccess).not.toHaveBeenCalled();
  });
  it("adds schedule_task and the data-table tools once their feature flags put them on the list", async () => {
    const api = mockApi({
      listAgents: vi.fn().mockResolvedValue([agent("a-1", "Assistant", { allowedToolNames: ["web_search"] })]),
      listAvailableTools: vi
        .fn()
        .mockResolvedValue([{ name: "web_search" }, { name: "schedule_task" }, { name: "data_table_query" }, { name: "data_table_write" }]),
    });
    expect(await syncAssistantTools(api, "a-1")).toBe(true);
    expect(api.setAgentToolAccess).toHaveBeenCalledWith("a-1", [
      "web_search",
      "schedule_task",
      "data_table_query",
      "data_table_write",
    ]);
  });
});
