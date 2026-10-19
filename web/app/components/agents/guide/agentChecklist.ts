// "Create your agent" checklist - the pure half (2026-09-19).
//
// Founder feedback: "still users get lost in creating agents - give them
// proper guidelines." The workbench is a 5,000-line build-and-test screen with
// ~12 collapsible sections, and a first-time consumer lands on it with a blank
// name, a placeholder prompt and no indication of which of those sections
// matter. AgentsGuide's modal explains the flow once and then closes; nothing
// on the screen afterwards says "you are here, this is next".
//
// Everything in this file is a pure function of its inputs: state the
// workbench already holds, plus two things the checklist component reads
// (the channel status list and a per-browser "has chatted" flag). Rules it is
// built around (same posture as TemplateSetupGuide's honesty rules):
//   - "done" means something true of THIS agent that the person did or chose:
//     a name and instructions, collections or tools they picked, a reply they
//     got in the Playground, a channel whose answering agent is this one.
//   - A setting that is merely on by default (auto-selected tools, knowledge
//     search in auto mode) is "auto" ("Automatic"), and is left out of the
//     progress count entirely, so an untouched draft reads "0 of N done".
//   - A failed or missing read is "unknown" ("not checked"), never "todo" and
//     never "done".
//   - The "tested" input is per-browser evidence (see CreateAgentChecklist's
//     readTested): it can be missing for an agent that was tested elsewhere.
//   - Steps that need a saved agent (test, channel) are "locked" on a draft,
//     with the reason in words, instead of a button that does nothing.
//   - No React, no browser API, no app imports - so it is trivially testable
//     and safe to import from anywhere.

/** The exact string AgentWorkbench seeds a blank draft's prompt with (its
 * useState initial value AND startDraft()). A draft still holding it has not
 * been told what to do yet, so step 1 must not tick on it. Deliberate copy:
 * the workbench keeps the literal inline in two places and this module must
 * not import a 5,000-line client component to read one string. */
export const DEFAULT_DRAFT_PROMPT = "You are a concise, helpful assistant.";

/** Server-side limits the draft form already enforces via maxLength. */
export const NAME_MAX = 255;
export const PROMPT_MAX = 20000;

/** DOM ids the checklist scrolls to. The workbench puts these on the real
 * controls (name input, persona/knowledge/tools sections, playground pane);
 * the describe box is the checklist's own. */
export const GUIDE_ANCHORS = {
  describe: "agent-guide-describe",
  name: "agent-guide-name",
  instructions: "agent-guide-instructions",
  knowledge: "agent-guide-knowledge",
  tools: "agent-guide-tools",
  playground: "agent-guide-playground",
} as const;

export type GuideAnchor = (typeof GUIDE_ANCHORS)[keyof typeof GUIDE_ANCHORS];

export type GuideStepId = "describe" | "knowledge" | "tools" | "test" | "channel";

/**
 * done     - true of this agent right now, because of something the person did or chose.
 * todo     - the person can do this now and has not.
 * optional - skippable, and not done. Never the "next" step.
 * auto     - handled by a default the person did not set. Rendered
 *            "Automatic"; not counted in progress at all (neither side).
 * locked   - cannot be done until the draft is saved.
 * unknown  - we could not check (failed read). Rendered "not checked".
 */
export type GuideStepStatus = "done" | "todo" | "optional" | "auto" | "locked" | "unknown";

export type GuideAction =
  | { kind: "anchor"; anchor: GuideAnchor; label: string }
  | { kind: "link"; href: string; label: string }
  | { kind: "save"; label: string };

export interface GuideStep {
  id: GuideStepId;
  title: string;
  /** One plain sentence: what this is and why it matters. */
  help: string;
  status: GuideStepStatus;
  /** One short line describing the CURRENT state, in words. */
  detail: string;
  action: GuideAction;
}

export interface ChecklistInput {
  isDraft: boolean;
  name: string;
  systemPrompt: string;
  /** "SIMPLE" (auto-select) or "ADVANCED" (manual). Anything else is treated as SIMPLE. */
  mode: string;
  knowledgeBaseEnabled: boolean;
  /** Knowledge collections that exist in the workspace. */
  collectionCount: number;
  /** Collections this agent is restricted to. 0 = unrestricted (AgentResponse.java). */
  allowedCollectionCount: number;
  /** Tools this agent is restricted to. 0 = unrestricted (AgentResponse.java). */
  allowedToolCount: number;
  /** A Playground reply from this agent has been seen in this browser. */
  tested: boolean;
  /** The saved agent's id; null on a draft. Used to match a channel's answering agent. */
  agentId: string | null;
  /** CONNECTED channels in the workspace; null = could not check. */
  connectedChannels: ConnectedChannel[] | null;
}

/** One CONNECTED row of GET /api/channels/status. */
export interface ConnectedChannel {
  label: string;
  /** ChannelStatusEntry.defaultAgentId: the agent that answers on this channel. null = none chosen. */
  defaultAgentId: string | null;
}

/** "WhatsApp", "WhatsApp and Email", "WhatsApp, Telegram and Email". */
function joinLabels(labels: string[]): string {
  if (labels.length <= 1) return labels[0] ?? "";
  return `${labels.slice(0, -1).join(", ")} and ${labels[labels.length - 1]}`;
}

function plural(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** Has step 1 really been done - a name, and instructions that are not the placeholder? */
export function describeState(input: Pick<ChecklistInput, "isDraft" | "name" | "systemPrompt">): {
  done: boolean;
  missingName: boolean;
  missingInstructions: boolean;
  placeholderInstructions: boolean;
} {
  if (!input.isDraft) {
    // A saved agent passed the server's @NotBlank on both fields.
    return { done: true, missingName: false, missingInstructions: false, placeholderInstructions: false };
  }
  const missingName = !input.name.trim();
  const prompt = input.systemPrompt.trim();
  const missingInstructions = !prompt;
  const placeholderInstructions = prompt === DEFAULT_DRAFT_PROMPT;
  return {
    done: !missingName && !missingInstructions && !placeholderInstructions,
    missingName,
    missingInstructions,
    placeholderInstructions,
  };
}

export function deriveChecklist(input: ChecklistInput): GuideStep[] {
  const d = describeState(input);
  const auto = input.mode !== "ADVANCED";

  // ---- 1. What should it do? -------------------------------------------
  let describeDetail: string;
  if (d.done) {
    describeDetail = input.isDraft
      ? "It has a name and instructions. Press Save draft to create it."
      : "It has a name and instructions.";
  } else if (d.missingName && (d.missingInstructions || d.placeholderInstructions)) {
    describeDetail = "Not started. Describe it in one sentence below and we will draft the name and instructions.";
  } else if (d.missingName) {
    describeDetail = "The instructions are written. It still needs a name.";
  } else if (d.missingInstructions) {
    describeDetail = "It has a name. The instructions box is empty.";
  } else {
    describeDetail = "It has a name, but the instructions are still the placeholder text.";
  }
  const describe: GuideStep = {
    id: "describe",
    title: "What should it do?",
    help: "Say what the agent is for in one plain sentence. That becomes its name and its instructions, which you can edit afterwards.",
    status: d.done ? "done" : "todo",
    detail: describeDetail,
    action: d.done
      ? input.isDraft
        ? { kind: "save", label: "Save draft" }
        : { kind: "anchor", anchor: GUIDE_ANCHORS.instructions, label: "See the instructions" }
      : d.missingName && !d.missingInstructions && !d.placeholderInstructions
        ? { kind: "anchor", anchor: GUIDE_ANCHORS.name, label: "Name it" }
        : { kind: "anchor", anchor: GUIDE_ANCHORS.describe, label: "Describe it" },
  };

  // ---- 2. Give it knowledge (optional) ---------------------------------
  const knowledgeOn = input.isDraft ? auto || input.knowledgeBaseEnabled : input.knowledgeBaseEnabled;
  let knowledge: GuideStep;
  const knowledgeHelp =
    "Upload your own documents, such as a price list or a returns policy, so the agent answers from them instead of guessing.";
  if (input.collectionCount <= 0) {
    knowledge = {
      id: "knowledge",
      title: "Give it knowledge (optional)",
      help: knowledgeHelp,
      status: "optional",
      detail: "No documents uploaded in this workspace yet. You can skip this and add them later.",
      action: { kind: "link", href: "/knowledge", label: "Upload documents" },
    };
  } else if (knowledgeOn) {
    // Done only for a choice the person made: collections picked for this
    // agent, or knowledge switched on by hand in Manual mode. In auto mode
    // knowledge search is simply on by default - "Automatic", not a tick.
    const chosen = !input.isDraft && input.allowedCollectionCount > 0;
    knowledge = {
      id: "knowledge",
      title: "Give it knowledge (optional)",
      help: knowledgeHelp,
      status: chosen || !auto ? "done" : "auto",
      detail: chosen
        ? `Answers from ${plural(input.allowedCollectionCount, "chosen collection", "chosen collections")}.`
        : auto
          ? `Automatic: it can search ${plural(input.collectionCount, "collection", "collections")} in this workspace. Nothing to set up.`
          : `Can search ${plural(input.collectionCount, "collection", "collections")} in this workspace.`,
      action: { kind: "anchor", anchor: GUIDE_ANCHORS.knowledge, label: "Review" },
    };
  } else {
    knowledge = {
      id: "knowledge",
      title: "Give it knowledge (optional)",
      help: knowledgeHelp,
      status: "optional",
      detail: `Knowledge is switched off for this agent. ${plural(input.collectionCount, "collection is", "collections are")} available.`,
      action: { kind: "anchor", anchor: GUIDE_ANCHORS.knowledge, label: "Open knowledge" },
    };
  }

  // ---- 3. Pick what it can do ------------------------------------------
  // Never "todo": every setting here is a valid finished state. Auto-select
  // picks tools per message; Manual with nothing ticked means NO restriction
  // (AgentResponse.java: "empty means no restriction, not none"). Only tools
  // the person actually ticked for a saved agent is "done". Auto-select is a
  // default at work ("Automatic", not counted); Manual with nothing chosen
  // yet is "optional".
  const toolsChosen = !auto && !input.isDraft && input.allowedToolCount > 0;
  const tools: GuideStep = {
    id: "tools",
    title: "Pick what it can do",
    help: "Tools are actions beyond talking, such as looking something up or sending a message. Before a consequential action the agent asks for your approval.",
    status: toolsChosen ? "done" : auto ? "auto" : "optional",
    detail: auto
      ? "Automatic: the agent picks the right tool for each message. Nothing to set up."
      : input.isDraft
        ? "Manual: you choose the exact tools right after saving."
        : input.allowedToolCount > 0
          ? `Manual: limited to ${plural(input.allowedToolCount, "tool", "tools")} you chose.`
          : "Manual, with nothing ticked: it may use any tool switched on in this workspace.",
    action: { kind: "anchor", anchor: GUIDE_ANCHORS.tools, label: auto ? "See the options" : "Choose tools" },
  };

  // ---- 4. Test it --------------------------------------------------------
  const test: GuideStep = {
    id: "test",
    title: "Test it",
    help: "Chat with your agent in the Playground on this page. Only you can see it, so it is a safe place to try awkward questions.",
    status: input.isDraft ? "locked" : input.tested ? "done" : "todo",
    detail: input.isDraft
      ? "Save the draft first. The Playground talks to a saved agent."
      : input.tested
        ? "This agent has replied to you in the Playground."
        : "Send it the kind of message a customer would send.",
    action: input.isDraft
      ? { kind: "save", label: "Save draft" }
      : { kind: "anchor", anchor: GUIDE_ANCHORS.playground, label: "Open the Playground" },
  };

  // ---- 5. Put it on a channel -------------------------------------------
  let channelStatus: GuideStepStatus;
  let channelDetail: string;
  if (input.isDraft) {
    channelStatus = "locked";
    channelDetail = "Save the draft first, then connect a channel.";
  } else if (input.connectedChannels === null) {
    channelStatus = "unknown";
    channelDetail = "We could not check your channels just now. Open Channels to see them.";
  } else if (input.connectedChannels.length > 0) {
    // A connected channel only counts for THIS agent when the channel's
    // answering agent (ChannelStatusEntry.defaultAgentId) is this agent.
    const mine = input.agentId
      ? input.connectedChannels.filter((c) => c.defaultAgentId !== null && c.defaultAgentId === input.agentId)
      : [];
    if (mine.length > 0) {
      channelStatus = "done";
      channelDetail = `${joinLabels(mine.map((c) => c.label))} ${mine.length === 1 ? "answers" : "answer"} with this agent.`;
    } else {
      channelStatus = "todo";
      const taken = input.connectedChannels.filter((c) => c.defaultAgentId !== null);
      const unset = input.connectedChannels.filter((c) => c.defaultAgentId === null);
      const parts: string[] = [];
      if (taken.length > 0) {
        parts.push(`${joinLabels(taken.map((c) => c.label))} ${taken.length === 1 ? "is" : "are"} connected but ${taken.length === 1 ? "answers" : "answer"} with another agent.`);
      }
      if (unset.length > 0) {
        parts.push(`${joinLabels(unset.map((c) => c.label))} ${unset.length === 1 ? "is" : "are"} connected with no answering agent chosen.`);
      }
      parts.push("Open Channels to make this agent the one that answers.");
      channelDetail = parts.join(" ");
    }
  } else {
    channelStatus = "todo";
    channelDetail = "No channel connected yet. WhatsApp, Telegram and Email are on the Free plan.";
  }
  const channel: GuideStep = {
    id: "channel",
    title: "Put it on a channel",
    help: "A channel is where your customers talk to the agent, for example your WhatsApp number or your support email.",
    status: channelStatus,
    detail: channelDetail,
    action: input.isDraft ? { kind: "save", label: "Save draft" } : { kind: "link", href: "/admin/channels", label: "Open Channels" },
  };

  return [describe, knowledge, tools, test, channel];
}

/** "2 of 4". "auto" steps are in neither number: a default the person did
 * not set is not progress, and not something left to do either.
 * optional/locked/unknown steps count toward the total but not as done. */
export function checklistProgress(steps: GuideStep[]): { done: number; total: number } {
  const counted = steps.filter((s) => s.status !== "auto");
  return { done: counted.filter((s) => s.status === "done").length, total: counted.length };
}

/** The one step to point at: the first the person can act on now and has not. */
export function nextStep(steps: GuideStep[]): GuideStep | null {
  return steps.find((s) => s.status === "todo") ?? null;
}

// ---------------------------------------------------------------------------
// Save-time validation, in a consumer's words, pointing at the field.
//
// AgentWorkbench#validate returned "Name is required." / "System prompt is
// required." / "Activate a provider first." in a banner at the top of a very
// tall page - correct, but it names a field in jargon and does not say where
// it is. findCreateBlocker mirrors validate()'s exact order and conditions so
// the two can never disagree about WHETHER a save is blocked; it only changes
// what the person is told and where the page takes them.
// ---------------------------------------------------------------------------

export type CreateBlockerCode = "name" | "instructions" | "provider" | "agent-limit";

export interface CreateBlocker {
  code: CreateBlockerCode;
  message: string;
  /** Where to take the person. Null when the fix is not on this screen. */
  anchor: GuideAnchor | null;
}

export function findCreateBlocker(input: {
  name: string;
  systemPrompt: string;
  needsProvider: boolean;
  atAgentLimit: boolean;
  agentLimit?: number | null;
}): CreateBlocker | null {
  if (!input.name.trim()) {
    return {
      code: "name",
      message: "Your agent needs a name before it can be saved. Type one in the highlighted box, for example \"Shop helper\".",
      anchor: GUIDE_ANCHORS.name,
    };
  }
  if (!input.systemPrompt.trim()) {
    return {
      code: "instructions",
      message:
        "The instructions box is empty, so the agent would not know what to do. Write a sentence or two in the highlighted box, or use \"Describe it in one sentence\" in the checklist.",
      anchor: GUIDE_ANCHORS.instructions,
    };
  }
  if (input.needsProvider) {
    return {
      code: "provider",
      message:
        "This workspace has no AI model switched on yet, so the agent could not reply. Open Providers and turn one on, then come back and save.",
      anchor: null,
    };
  }
  if (input.atAgentLimit) {
    const limit = typeof input.agentLimit === "number" && Number.isFinite(input.agentLimit) ? input.agentLimit : null;
    return {
      code: "agent-limit",
      message:
        limit !== null
          ? `Your plan allows ${plural(limit, "agent", "agents")} and you already have that many. To add another, upgrade your plan, or remove an agent you no longer use (removing agents needs a paid plan).`
          : "You have reached your plan's agent limit. To add another, upgrade your plan, or remove an agent you no longer use (removing agents needs a paid plan).",
      anchor: null,
    };
  }
  return null;
}

// ---------------------------------------------------------------------------
// "Describe it in one sentence" -> name + instructions.
//
// Reuses the existing Ask-AI endpoint (POST /api/assist/draft, the endpoint
// AskAiHelper uses). ONE call drafts both fields: the instruction asks for a
// leading "NAME:" line, and parseDraftReply tolerates a model that ignores
// that.
//
// That endpoint is NOT available to every tenant. AssistService sends only
// plan == FREE tenants to the hosted pool; everyone else needs an active
// provider key, so a workspace on the complimentary signup grant with no key
// (and a paid workspace using the platform model with no key) gets HTTP 400
// every time. buildFallbackDraft is therefore a first-class path, not an edge
// case: for those tenants it is the ONLY draft they get. It needs no network
// and no model, and produces a usable name and instructions from the sentence
// alone, so the person is never left with an error and an empty form.
// ---------------------------------------------------------------------------

export const DESCRIBE_FIELD_LABEL = "Agent system prompt";
export const DESCRIBE_MIN_CHARS = 8;
export const DESCRIBE_MAX_CHARS = 400;

export interface AgentDraft {
  name: string;
  systemPrompt: string;
}

export function normalizeSentence(raw: string): string {
  return (raw || "").replace(/\s+/g, " ").trim().slice(0, DESCRIBE_MAX_CHARS);
}

export function buildDescribeInstruction(sentence: string): string {
  const s = normalizeSentence(sentence);
  return (
    `The owner described the agent they want in one sentence: "${s}". ` +
    "Write that agent's system prompt in plain second person (\"You are ...\"): who it is, what it helps with, " +
    "its tone, and what it must not do. It must never invent prices, stock, policies or other facts, and it must " +
    "hand anything needing a human decision back to the owner. " +
    "Begin your reply with one line in the exact form NAME: <a 2 to 4 word name for this agent>, then a blank line, " +
    "then the system prompt."
  );
}

const NAME_STOP_WORDS = new Set([
  "a", "an", "the", "my", "our", "your", "to", "for", "of", "and", "or", "that", "who", "which", "i", "we",
  "want", "need", "needs", "would", "like", "please", "should", "can", "could", "will", "it", "is", "be",
  "agent", "assistant", "bot", "ai", "help", "helps", "me", "us", "with", "about", "on", "in", "at", "from",
]);

/** A usable name from the sentence alone: up to three meaningful words + "assistant". */
export function fallbackName(sentence: string): string {
  const words = normalizeSentence(sentence)
    // \p{M} matters: Indic vowel signs are combining marks, and stripping
    // them turned "दुकान" into "द क न".
    .replace(/[^\p{L}\p{M}\p{N}\s'-]/gu, " ")
    .split(/\s+/)
    .filter((w) => w && !NAME_STOP_WORDS.has(w.toLowerCase()));
  const picked = words.slice(0, 3).join(" ");
  if (!picked) return "My assistant";
  const titled = picked.charAt(0).toUpperCase() + picked.slice(1);
  return `${titled} assistant`.slice(0, 60);
}

export function buildFallbackDraft(sentence: string): AgentDraft {
  const s = normalizeSentence(sentence).replace(/[.!?\s]+$/u, "");
  return {
    name: fallbackName(sentence),
    systemPrompt: `You are a friendly, capable assistant. Your job: ${s}.

How to behave:
- Answer in the language the person writes in.
- Keep replies short and concrete. Prefer a worked example over a description of what you could do.
- Sound like a capable colleague, not a chatbot.

What you must be straight about:
- Never invent prices, stock, policies or other facts. If you were given documents or notes, answer from those; otherwise ask for the detail or say you do not know.
- If something needs a human decision (a refund, a discount, a medical or legal question), say so plainly and offer to note it down for the owner instead of guessing.`,
  };
}

function stripWrapping(text: string): string {
  let out = text.trim();
  // Code fences and wrapping quotes the assist prompt forbids but small models add anyway.
  out = out.replace(/^```[a-zA-Z]*\s*\n?/, "").replace(/\n?```\s*$/, "").trim();
  if (out.length > 1 && /^["'“”]/.test(out) && /["'“”]$/.test(out)) out = out.slice(1, -1).trim();
  return out;
}

/** Splits the model's reply into a name and a prompt. Never throws, never returns an empty field. */
export function parseDraftReply(reply: unknown, sentence: string): AgentDraft {
  const fallback = buildFallbackDraft(sentence);
  if (typeof reply !== "string" || !reply.trim()) return fallback;
  const text = stripWrapping(reply);
  const match = text.match(/^\s*\**\s*name\s*\**\s*[:\-]\s*(.+)$/im);
  let name = "";
  let body = text;
  // Only honour a NAME line near the top: a "Name:" deep inside the prompt is content.
  if (match && match.index !== undefined && text.slice(0, match.index).trim().length === 0) {
    name = match[1].replace(/[*_`"'“”]/g, "").trim();
    body = text.slice(match.index + match[0].length).trim();
  }
  if (!name || name.length > 60) name = fallback.name;
  if (!body) body = fallback.systemPrompt;
  return { name: name.slice(0, NAME_MAX), systemPrompt: body.slice(0, PROMPT_MAX) };
}

// ---------------------------------------------------------------------------
// GET /api/channels/status -> the connected channels, each with the agent
// that answers on it (ChannelStatusEntry.defaultAgentId in
// ChannelStatusController). Same read TemplateSetupGuide's snapshot makes;
// null means "could not tell".
// ---------------------------------------------------------------------------
export function parseConnectedChannels(raw: unknown): ConnectedChannel[] | null {
  if (!raw || typeof raw !== "object") return null;
  const list = (raw as Record<string, unknown>).channels;
  if (!Array.isArray(list)) return null;
  const out: ConnectedChannel[] = [];
  for (const c of list) {
    if (!c || typeof c !== "object") continue;
    const rec = c as Record<string, unknown>;
    if (rec.state !== "CONNECTED") continue;
    const label = String(rec.label ?? rec.key ?? "").trim();
    if (!label) continue;
    const id = typeof rec.defaultAgentId === "string" ? rec.defaultAgentId.trim() : "";
    out.push({ label, defaultAgentId: id || null });
  }
  return out;
}

/** Is the draft still exactly as startDraft() left it? Drives the empty state. */
export function isPristineDraft(input: { isDraft: boolean; name: string; systemPrompt: string; templateKey: string | null }): boolean {
  if (!input.isDraft || input.templateKey) return false;
  const prompt = input.systemPrompt.trim();
  return !input.name.trim() && (prompt === "" || prompt === DEFAULT_DRAFT_PROMPT);
}
