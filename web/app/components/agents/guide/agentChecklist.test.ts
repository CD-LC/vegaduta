import { describe, expect, it } from "vitest";
import {
  DEFAULT_DRAFT_PROMPT,
  DESCRIBE_MAX_CHARS,
  GUIDE_ANCHORS,
  NAME_MAX,
  PROMPT_MAX,
  buildDescribeInstruction,
  buildFallbackDraft,
  checklistProgress,
  deriveChecklist,
  describeState,
  fallbackName,
  findCreateBlocker,
  isPristineDraft,
  nextStep,
  normalizeSentence,
  parseConnectedChannels,
  parseDraftReply,
  type ChecklistInput,
} from "./agentChecklist";
import { AGENT_GLOSSARY } from "./glossary";

const blankDraft: ChecklistInput = {
  isDraft: true,
  name: "",
  systemPrompt: DEFAULT_DRAFT_PROMPT,
  mode: "SIMPLE",
  knowledgeBaseEnabled: false,
  collectionCount: 0,
  allowedCollectionCount: 0,
  allowedToolCount: 0,
  tested: false,
  agentId: null,
  connectedChannels: null,
};

const savedAgent: ChecklistInput = {
  ...blankDraft,
  isDraft: false,
  name: "Shop helper",
  systemPrompt: "You answer for the shop.",
  agentId: "agent-1",
  connectedChannels: [],
};

function step(input: ChecklistInput, id: string) {
  const found = deriveChecklist(input).find((s) => s.id === id);
  if (!found) throw new Error(`no step ${id}`);
  return found;
}

describe("describeState", () => {
  it("does not count the workbench's placeholder prompt as instructions", () => {
    const d = describeState({ isDraft: true, name: "Shop helper", systemPrompt: `  ${DEFAULT_DRAFT_PROMPT}  ` });
    expect(d.done).toBe(false);
    expect(d.placeholderInstructions).toBe(true);
  });

  it("is done for a draft with a name and real instructions", () => {
    expect(describeState({ isDraft: true, name: "A", systemPrompt: "You help." }).done).toBe(true);
  });

  it("treats whitespace-only fields as missing", () => {
    const d = describeState({ isDraft: true, name: "   ", systemPrompt: "\n " });
    expect(d.missingName).toBe(true);
    expect(d.missingInstructions).toBe(true);
  });

  it("is always done for a saved agent", () => {
    expect(describeState({ isDraft: false, name: "", systemPrompt: "" }).done).toBe(true);
  });
});

describe("deriveChecklist", () => {
  it("always returns the five steps in order", () => {
    expect(deriveChecklist(blankDraft).map((s) => s.id)).toEqual(["describe", "knowledge", "tools", "test", "channel"]);
  });

  it("blank draft: step 1 is next and points at the describe box; test and channel are locked", () => {
    const steps = deriveChecklist(blankDraft);
    expect(nextStep(steps)?.id).toBe("describe");
    expect(steps[0].action).toEqual({ kind: "anchor", anchor: GUIDE_ANCHORS.describe, label: "Describe it" });
    expect(step(blankDraft, "test").status).toBe("locked");
    expect(step(blankDraft, "channel").status).toBe("locked");
    expect(step(blankDraft, "test").action.kind).toBe("save");
  });

  it("draft with instructions from a template but no name is sent to the name box", () => {
    const s = step({ ...blankDraft, systemPrompt: "You are the clinic's assistant." }, "describe");
    expect(s.status).toBe("todo");
    expect(s.action).toMatchObject({ kind: "anchor", anchor: GUIDE_ANCHORS.name });
  });

  it("finished draft: step 1 is done and its button saves", () => {
    const s = step({ ...blankDraft, name: "Clinic desk", systemPrompt: "You are the clinic's assistant." }, "describe");
    expect(s.status).toBe("done");
    expect(s.action.kind).toBe("save");
  });

  it("knowledge is optional, never todo, and links to uploads when the workspace has no documents", () => {
    const s = step(blankDraft, "knowledge");
    expect(s.status).toBe("optional");
    expect(s.action).toMatchObject({ kind: "link", href: "/knowledge" });
  });

  it("knowledge ticks only for a choice the person made: picked collections, or switched on by hand in Manual", () => {
    // Default-on in auto mode is "Automatic", not a tick.
    const autoOn = step({ ...savedAgent, collectionCount: 2, knowledgeBaseEnabled: true }, "knowledge");
    expect(autoOn.status).toBe("auto");
    expect(autoOn.detail).toMatch(/^Automatic/);
    expect(step({ ...savedAgent, collectionCount: 2, knowledgeBaseEnabled: true, allowedCollectionCount: 1 }, "knowledge").status).toBe("done");
    expect(step({ ...savedAgent, mode: "ADVANCED", collectionCount: 2, knowledgeBaseEnabled: true }, "knowledge").status).toBe("done");
    expect(step({ ...savedAgent, collectionCount: 2, knowledgeBaseEnabled: false }, "knowledge").status).toBe("optional");
    expect(step({ ...savedAgent, collectionCount: 0, knowledgeBaseEnabled: true }, "knowledge").status).toBe("optional");
  });

  it("an auto-select draft has knowledge on regardless of the checkbox (createAgent sends true for SIMPLE) - shown as Automatic", () => {
    expect(step({ ...blankDraft, collectionCount: 1, mode: "SIMPLE" }, "knowledge").status).toBe("auto");
    expect(step({ ...blankDraft, collectionCount: 1, mode: "ADVANCED" }, "knowledge").status).toBe("optional");
    expect(step({ ...blankDraft, collectionCount: 1, mode: "ADVANCED", knowledgeBaseEnabled: true }, "knowledge").status).toBe("done");
  });

  it("says which collections a restricted agent answers from", () => {
    const s = step({ ...savedAgent, collectionCount: 5, knowledgeBaseEnabled: true, allowedCollectionCount: 1 }, "knowledge");
    expect(s.detail).toContain("1 chosen collection.");
  });

  it("tools: manual with nothing ticked is described as unrestricted, not as 'no tools'", () => {
    const s = step({ ...savedAgent, mode: "ADVANCED", allowedToolCount: 0 }, "tools");
    expect(s.status).toBe("optional");
    expect(s.detail).toMatch(/any tool/);
    expect(step({ ...savedAgent, mode: "ADVANCED", allowedToolCount: 3 }, "tools").detail).toContain("3 tools");
    expect(step(savedAgent, "tools").detail).toMatch(/^Automatic/);
  });

  it("tools: only tools the person ticked is Done; auto-select is Automatic, never a tick or a to-do", () => {
    expect(step(blankDraft, "tools").status).toBe("auto");
    expect(step(savedAgent, "tools").status).toBe("auto");
    expect(step({ ...savedAgent, mode: "ADVANCED", allowedToolCount: 3 }, "tools").status).toBe("done");
    expect(step({ ...blankDraft, mode: "ADVANCED" }, "tools").status).toBe("optional");
    // A stale allow-list on an auto-select agent is not in force, so no tick.
    expect(step({ ...savedAgent, mode: "SIMPLE", allowedToolCount: 3 }, "tools").status).toBe("auto");
  });

  it("an unrecognised mode is treated as auto-select", () => {
    expect(step({ ...savedAgent, mode: "" }, "tools").detail).toMatch(/^Automatic/);
  });

  it("test step follows the tested flag on a saved agent", () => {
    expect(step(savedAgent, "test").status).toBe("todo");
    expect(step({ ...savedAgent, tested: true }, "test").status).toBe("done");
    expect(step(savedAgent, "test").action).toMatchObject({ kind: "anchor", anchor: GUIDE_ANCHORS.playground });
  });

  it("channel: a failed read is 'unknown', never 'todo' or 'done'", () => {
    expect(step({ ...savedAgent, connectedChannels: null }, "channel").status).toBe("unknown");
    expect(step({ ...savedAgent, connectedChannels: [] }, "channel").status).toBe("todo");
  });

  it("channel: ticks only when a connected channel answers with THIS agent", () => {
    const done = step(
      {
        ...savedAgent,
        connectedChannels: [
          { label: "WhatsApp", defaultAgentId: "agent-1" },
          { label: "Email", defaultAgentId: "agent-1" },
          { label: "Telegram", defaultAgentId: "agent-2" },
        ],
      },
      "channel"
    );
    expect(done.status).toBe("done");
    expect(done.detail).toBe("WhatsApp and Email answer with this agent.");
  });

  it("channel: connected but bound to another agent is a to-do that says so", () => {
    const other = step({ ...savedAgent, connectedChannels: [{ label: "WhatsApp", defaultAgentId: "agent-2" }] }, "channel");
    expect(other.status).toBe("todo");
    expect(other.detail).toContain("WhatsApp is connected but answers with another agent.");
    expect(other.detail).toContain("Open Channels");
  });

  it("channel: connected with no answering agent chosen is a to-do, never a tick", () => {
    const unset = step({ ...savedAgent, connectedChannels: [{ label: "Email", defaultAgentId: null }] }, "channel");
    expect(unset.status).toBe("todo");
    expect(unset.detail).toContain("Email is connected with no answering agent chosen.");
  });

  it("channel: never ticks without an agent id to match against", () => {
    const s = step({ ...savedAgent, agentId: null, connectedChannels: [{ label: "Email", defaultAgentId: null }] }, "channel");
    expect(s.status).toBe("todo");
  });

  it("names the three Free-plan channels and nothing else as free", () => {
    expect(step(savedAgent, "channel").detail).toContain("WhatsApp, Telegram and Email are on the Free plan");
  });

  it("progress counts only real ticks", () => {
    // Untouched draft: nothing is done, and the Automatic tools step is in neither number.
    expect(checklistProgress(deriveChecklist(blankDraft))).toEqual({ done: 0, total: 4 });
    // With documents in the workspace, default-on knowledge is Automatic too.
    expect(checklistProgress(deriveChecklist({ ...blankDraft, collectionCount: 2 }))).toEqual({ done: 0, total: 3 });
    const all = deriveChecklist({
      ...savedAgent,
      mode: "ADVANCED",
      collectionCount: 1,
      knowledgeBaseEnabled: true,
      allowedCollectionCount: 1,
      allowedToolCount: 2,
      tested: true,
      connectedChannels: [{ label: "Telegram", defaultAgentId: "agent-1" }],
    });
    expect(checklistProgress(all)).toEqual({ done: 5, total: 5 });
    expect(nextStep(all)).toBeNull();
    // Auto-select saved agent, tested and on a channel: 3 of 4, tools not counted.
    const autoAgent = deriveChecklist({
      ...savedAgent,
      tested: true,
      connectedChannels: [{ label: "Telegram", defaultAgentId: "agent-1" }],
    });
    expect(checklistProgress(autoAgent)).toEqual({ done: 3, total: 4 });
  });

  it("every step has help, detail and a labelled action", () => {
    for (const input of [blankDraft, savedAgent]) {
      for (const s of deriveChecklist(input)) {
        expect(s.help.length).toBeGreaterThan(20);
        expect(s.detail.length).toBeGreaterThan(10);
        expect(s.action.label.length).toBeGreaterThan(2);
      }
    }
  });
});

describe("findCreateBlocker mirrors AgentWorkbench#validate's order", () => {
  const ok = { name: "A", systemPrompt: "B", needsProvider: false, atAgentLimit: false };

  it("returns null when nothing blocks", () => {
    expect(findCreateBlocker(ok)).toBeNull();
  });

  it("name first, pointing at the name box", () => {
    const b = findCreateBlocker({ ...ok, name: " ", systemPrompt: "", needsProvider: true, atAgentLimit: true });
    expect(b?.code).toBe("name");
    expect(b?.anchor).toBe(GUIDE_ANCHORS.name);
  });

  it("then instructions, without the words 'system prompt'", () => {
    const b = findCreateBlocker({ ...ok, systemPrompt: "  " });
    expect(b?.code).toBe("instructions");
    expect(b?.anchor).toBe(GUIDE_ANCHORS.instructions);
    expect(b?.message.toLowerCase()).not.toContain("system prompt");
  });

  it("then provider, then the agent limit - neither has a field on this screen", () => {
    expect(findCreateBlocker({ ...ok, needsProvider: true, atAgentLimit: true })).toMatchObject({ code: "provider", anchor: null });
    const limit = findCreateBlocker({ ...ok, atAgentLimit: true, agentLimit: 1 });
    expect(limit).toMatchObject({ code: "agent-limit", anchor: null });
    expect(limit?.message).toContain("1 agent ");
  });

  it("does not print Infinity or NaN for an uncapped or unknown limit", () => {
    for (const agentLimit of [Infinity, NaN, null, undefined]) {
      const msg = findCreateBlocker({ ...ok, atAgentLimit: true, agentLimit })?.message ?? "";
      expect(msg).not.toMatch(/Infinity|NaN|null|undefined/);
    }
  });
});

describe("describe-in-a-sentence drafting", () => {
  it("normalizes whitespace and caps the length", () => {
    expect(normalizeSentence("  answer \n  questions  ")).toBe("answer questions");
    expect(normalizeSentence("x".repeat(DESCRIBE_MAX_CHARS + 50))).toHaveLength(DESCRIBE_MAX_CHARS);
    expect(normalizeSentence(undefined as unknown as string)).toBe("");
  });

  it("the instruction carries the sentence and asks for a NAME line", () => {
    const i = buildDescribeInstruction("Take cake orders");
    expect(i).toContain('"Take cake orders"');
    expect(i).toContain("NAME:");
  });

  it("parses a well-formed reply", () => {
    const d = parseDraftReply("NAME: Cake Desk\n\nYou are the bakery's assistant.\nBe brief.", "take cake orders");
    expect(d).toEqual({ name: "Cake Desk", systemPrompt: "You are the bakery's assistant.\nBe brief." });
  });

  it("tolerates markdown, quotes and code fences around the reply", () => {
    expect(parseDraftReply("**Name:** *Cake Desk*\nYou are X.", "s").name).toBe("Cake Desk");
    expect(parseDraftReply("```\nNAME: Cake Desk\n\nYou are X.\n```", "s")).toEqual({ name: "Cake Desk", systemPrompt: "You are X." });
    expect(parseDraftReply('"NAME: Cake Desk\n\nYou are X."', "s").systemPrompt).toBe("You are X.");
  });

  it("keeps the whole reply as instructions when the model ignored the NAME line", () => {
    const d = parseDraftReply("You are the bakery's assistant.", "take cake orders for my bakery");
    expect(d.systemPrompt).toBe("You are the bakery's assistant.");
    expect(d.name).toBe(fallbackName("take cake orders for my bakery"));
  });

  it("does not mistake a 'Name:' deep inside the prompt for the agent's name", () => {
    const reply = "You are a desk assistant.\nAlways ask for:\nName: the caller's full name";
    const d = parseDraftReply(reply, "front desk");
    expect(d.systemPrompt).toBe(reply);
  });

  it("never returns an empty field, whatever comes back", () => {
    for (const bad of [null, undefined, 42, "", "   ", "NAME: Only A Name", {}]) {
      const d = parseDraftReply(bad, "answer questions about my salon");
      expect(d.name.trim().length).toBeGreaterThan(0);
      expect(d.systemPrompt.trim().length).toBeGreaterThan(0);
    }
  });

  it("rejects an absurdly long name and clamps to the server limits", () => {
    const d = parseDraftReply(`NAME: ${"x".repeat(80)}\n\n${"y".repeat(PROMPT_MAX + 10)}`, "salon bookings");
    expect(d.name).toBe(fallbackName("salon bookings"));
    expect(d.name.length).toBeLessThanOrEqual(NAME_MAX);
    expect(d.systemPrompt).toHaveLength(PROMPT_MAX);
  });

  it("fallbackName drops filler words and has a default", () => {
    expect(fallbackName("I want an agent to answer questions for my bakery")).toBe("Answer questions bakery assistant");
    expect(fallbackName("")).toBe("My assistant");
    expect(fallbackName("an agent for me")).toBe("My assistant");
    expect(fallbackName("दुकान के ग्राहकों को जवाब दे")).toContain("दुकान");
  });

  it("the local fallback draft contains the sentence and the no-invention rule", () => {
    const d = buildFallbackDraft("Answer questions about my salon.");
    expect(d.systemPrompt).toContain("Your job: Answer questions about my salon.");
    expect(d.systemPrompt).not.toContain("salon..");
    expect(d.systemPrompt).toMatch(/Never invent/);
  });
});

describe("parseConnectedChannels", () => {
  it("returns only CONNECTED channels, by label then key, keeping the answering agent", () => {
    expect(
      parseConnectedChannels({
        channels: [
          { key: "whatsapp", label: "WhatsApp", state: "CONNECTED", defaultAgentId: "agent-1" },
          { key: "slack", label: "Slack", state: "NOT_CONFIGURED" },
          { key: "email", state: "CONNECTED", defaultAgentId: 42 },
          { state: "CONNECTED" },
          null,
        ],
      })
    ).toEqual([
      { label: "WhatsApp", defaultAgentId: "agent-1" },
      { label: "email", defaultAgentId: null },
    ]);
  });

  it("is null (not checked) for any shape it does not recognise", () => {
    for (const bad of [null, undefined, "x", 3, {}, { channels: "nope" }]) {
      expect(parseConnectedChannels(bad)).toBeNull();
    }
  });

  it("is an empty list (nothing connected) when the read worked", () => {
    expect(parseConnectedChannels({ channels: [] })).toEqual([]);
  });
});

describe("isPristineDraft", () => {
  it("is true only for an untouched, template-less draft", () => {
    expect(isPristineDraft({ isDraft: true, name: "", systemPrompt: DEFAULT_DRAFT_PROMPT, templateKey: null })).toBe(true);
    expect(isPristineDraft({ isDraft: true, name: "", systemPrompt: "", templateKey: null })).toBe(true);
    expect(isPristineDraft({ isDraft: true, name: "x", systemPrompt: DEFAULT_DRAFT_PROMPT, templateKey: null })).toBe(false);
    expect(isPristineDraft({ isDraft: true, name: "", systemPrompt: "custom", templateKey: null })).toBe(false);
    expect(isPristineDraft({ isDraft: true, name: "", systemPrompt: DEFAULT_DRAFT_PROMPT, templateKey: "starter:kirana" })).toBe(false);
    expect(isPristineDraft({ isDraft: false, name: "", systemPrompt: "", templateKey: null })).toBe(false);
  });
});

describe("glossary", () => {
  it("covers every jargon term the brief names, one sentence + one example each", () => {
    const keys = AGENT_GLOSSARY.map((g) => g.key);
    for (const k of ["instructions", "provider", "model", "temperature", "tools", "guardrails", "knowledge", "intelligence-source", "channels"]) {
      expect(keys).toContain(k);
    }
    expect(new Set(keys).size).toBe(keys.length);
    for (const g of AGENT_GLOSSARY) {
      expect(g.meaning.length).toBeGreaterThan(20);
      expect(g.example.length).toBeGreaterThan(20);
    }
  });

  it("stays off the do-not-claim list (PlatformLedger.tsx)", () => {
    const all = AGENT_GLOSSARY.map((g) => `${g.meaning} ${g.example}`).join(" ").toLowerCase();
    for (const banned of ["learns from", "learn from feedback", "reinforcement", "vault", "home assistant", "uptime", "coming soon", "% faster", "milliseconds"]) {
      expect(all).not.toContain(banned);
    }
  });
});
