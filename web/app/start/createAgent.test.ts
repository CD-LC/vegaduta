import { describe, expect, it, vi } from "vitest";
import type { AgentCapabilityEntry } from "../lib/api";
import {
  DEFAULT_PROVIDER_NAME,
  buildStartAgentPayload,
  createStartAgent,
  findConsoleTemplate,
  pickProviderName,
  type StartAgentApi,
} from "./createAgent";

function capability(over: Partial<AgentCapabilityEntry> = {}): AgentCapabilityEntry {
  return {
    key: "e2b-code-execution",
    label: "Code execution",
    description: "Run short snippets in a sandbox.",
    category: "tools",
    enabled: false,
    availability: "AVAILABLE",
    availabilityHint: null,
    ...over,
  } as AgentCapabilityEntry;
}

function stubApi(over: Partial<Record<keyof StartAgentApi, unknown>> = {}) {
  const api = {
    createAgent: vi.fn().mockResolvedValue({ id: "a1", name: "Created Name" }),
    listAgentCapabilities: vi.fn().mockResolvedValue([capability()]),
    setAgentCapability: vi.fn().mockResolvedValue({}),
    listAgentCapabilitiesQuiet: vi.fn().mockResolvedValue([]),
    setAgentCapabilityQuiet: vi.fn().mockResolvedValue({}),
    ...over,
  };
  return api as typeof api & StartAgentApi;
}

describe("buildStartAgentPayload", () => {
  it("matches what the workbench sends for an untouched draft", () => {
    const template = findConsoleTemplate("client-call-to-actions");
    expect(template).not.toBeNull();
    const payload = buildStartAgentPayload(template!, { name: "  My Agent  ", providerName: "ANTHROPIC" });
    expect(payload).toEqual({
      name: "My Agent",
      systemPrompt: template!.systemPrompt,
      providerName: "ANTHROPIC",
      maxTokens: 1024,
      temperature: 0.7,
      topP: 1.0,
      knowledgeBaseEnabled: true,
      experienceMode: "SIMPLE",
      tuningMode: "MANUAL",
    });
    // no model: the server picks a real id from the provider's catalogue
    expect(payload).not.toHaveProperty("model");
  });
});

describe("createStartAgent", () => {
  it("creates first, then runs the template's capability auto-enable", async () => {
    const api = stubApi({
      listAgentCapabilities: vi.fn().mockResolvedValue([capability({ availability: "NEEDS_SETUP", availabilityHint: "Add an E2B key." })]),
    });
    const created = await createStartAgent("data-analyst", { name: "Data Analyst", providerName: "ANTHROPIC" }, api);

    expect(created.id).toBe("a1");
    expect(api.createAgent).toHaveBeenCalledTimes(1);
    expect(api.listAgentCapabilities).toHaveBeenCalledWith("a1");
    expect(api.setAgentCapability).toHaveBeenCalledWith("a1", "e2b-code-execution", true);
    expect(vi.mocked(api.createAgent).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(api.listAgentCapabilities).mock.invocationCallOrder[0]);
    // honest outcome: switched on but not ready - the caller can say what is missing
    expect(created.capabilityOutcome.needsSetup).toEqual([{ label: "Code execution", hint: "Add an E2B key." }]);
  });

  it("makes no capability request at all for a template that declares none", async () => {
    const api = stubApi();
    const created = await createStartAgent("client-call-to-actions", { name: "Client Call", providerName: "ANTHROPIC" }, api);
    expect(api.listAgentCapabilities).not.toHaveBeenCalled();
    expect(api.setAgentCapability).not.toHaveBeenCalled();
    expect(api.listAgentCapabilitiesQuiet).not.toHaveBeenCalled();
    expect(created.capabilityOutcome).toEqual({ enabled: [], needsSetup: [], unavailable: [], failed: [] });
  });

  it("switches on the quiet bonus capability (research -> deep research) only when the server lists it available", async () => {
    const available = stubApi({
      listAgentCapabilitiesQuiet: vi.fn().mockResolvedValue([capability({ key: "deep-research", label: "Deep research" })]),
    });
    await createStartAgent("research-assistant", { name: "Research", providerName: "ANTHROPIC" }, available);
    expect(available.setAgentCapabilityQuiet).toHaveBeenCalledWith("a1", "deep-research", true);

    const unavailable = stubApi({
      listAgentCapabilitiesQuiet: vi.fn().mockResolvedValue([capability({ key: "deep-research", availability: "UNAVAILABLE" })]),
    });
    await createStartAgent("research-assistant", { name: "Research", providerName: "ANTHROPIC" }, unavailable);
    expect(unavailable.setAgentCapabilityQuiet).not.toHaveBeenCalled();
  });

  it("a capability failure never undoes or masks a successful create", async () => {
    const api = stubApi({
      listAgentCapabilities: vi.fn().mockRejectedValue(new Error("boom")),
      listAgentCapabilitiesQuiet: vi.fn().mockRejectedValue(new Error("boom")),
    });
    const created = await createStartAgent("data-analyst", { name: "Data Analyst", providerName: "ANTHROPIC" }, api);
    expect(created.id).toBe("a1");
    expect(created.capabilityOutcome.failed).toEqual(["Code execution"]);
  });

  it("surfaces a failed create and touches no capability", async () => {
    const api = stubApi({ createAgent: vi.fn().mockRejectedValue(new Error("Agent limit reached")) });
    await expect(createStartAgent("data-analyst", { name: "Data Analyst", providerName: "ANTHROPIC" }, api)).rejects.toThrow(
      "Agent limit reached"
    );
    expect(api.listAgentCapabilities).not.toHaveBeenCalled();
  });

  it("refuses a response with no id, an unknown template and a blank name", async () => {
    const noId = stubApi({ createAgent: vi.fn().mockResolvedValue({}) });
    await expect(createStartAgent("data-analyst", { name: "X", providerName: "ANTHROPIC" }, noId)).rejects.toThrow(/id/);
    const api = stubApi();
    await expect(createStartAgent("no-such-template", { name: "X", providerName: "ANTHROPIC" }, api)).rejects.toThrow(/Unknown agent template/);
    await expect(createStartAgent("data-analyst", { name: "   ", providerName: "ANTHROPIC" }, api)).rejects.toThrow(/name/);
    expect(api.createAgent).not.toHaveBeenCalled();
  });

  it("falls back to the requested name when the response omits one", async () => {
    const api = stubApi({ createAgent: vi.fn().mockResolvedValue({ id: "a9" }) });
    const created = await createStartAgent("client-call-to-actions", { name: " Client Call ", providerName: "ANTHROPIC" }, api);
    expect(created.name).toBe("Client Call");
  });
});

describe("pickProviderName", () => {
  it("prefers an ACTIVE provider, then any, then the workbench default", () => {
    expect(pickProviderName([{ providerName: "OPENAI", status: "INACTIVE" }, { providerName: "GEMINI", status: "ACTIVE" }])).toBe("GEMINI");
    expect(pickProviderName([{ providerName: "OPENAI", status: "INACTIVE" }])).toBe("OPENAI");
    expect(pickProviderName([])).toBe(DEFAULT_PROVIDER_NAME);
    expect(pickProviderName(null)).toBe(DEFAULT_PROVIDER_NAME);
    expect(pickProviderName([null, 3, {}])).toBe(DEFAULT_PROVIDER_NAME);
  });
});
