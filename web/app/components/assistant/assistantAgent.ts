import type { AgentResponse } from "../../lib/api/types";
import type { MemoryOverview } from "../../memory/memoryTypes";

/**
 * The per-user "Assistant" agent (design doc, gap G2 and "The Assistant agent (first run)"). Every chat
 * turn needs an agentId, so the surface finds or creates one agent per user with calls that already
 * exist, then marks it in a memory block so it is found again next time - the way Deep Research
 * provisions its own agent on demand.
 *
 * Lookup order: the agent id written in the marker block, then an agent named "Assistant", then create.
 * Creation: POST /api/agents (SIMPLE experience mode, the tenant's default provider and model), then
 * best-effort PATCHes - the tool allow-list (everything available minus payments and
 * http_request_write), knowledge base on - and the marker block. A PATCH that fails leaves a usable
 * agent behind; the Agents page can finish the setup.
 *
 * Pure except for the injected API, so it is tested against a mocked `api`.
 */
export const ASSISTANT_AGENT_NAME = "Assistant";

/**
 * core's MemoryBlocks accepts only persona | user_profile | project_notes | active_project |
 * project:<slug>, so the marker lives in a project note (the design doc's "blocks/assistant" would be
 * refused with 400). A project note is injected into a turn when the message mentions its slug - here
 * that is "assistant", and the note only carries the id and the person's name, locale and time zone.
 */
export const ASSISTANT_MARKER_LABEL = "project:assistant";

/** The first shipped prompt: an agent still carrying exactly this is upgraded in place, any edit is left alone. */
export const ASSISTANT_PROMPT_V1 = [
  "You are the user's personal assistant. You work until the request is finished.",
  "Plan briefly, act with your tools, verify the result, then report plainly what you did and what is left.",
  "Prefer using a tool over asking the user; ask only when a decision is genuinely theirs or an action is consequential (sending, paying, deleting, posting), and use request_human for that.",
  "When a request is too large for one sitting or should repeat, escalate yourself: schedule_task for routines, delegate_parallel for independent parts, deep_research for questions that need many sources.",
  "Save durable facts about the user with remember_fact. Never invent results; say when something could not be done.",
].join(" ");

/** V1 as the terminal commit shipped it (the shell line was added to V1 instead of the current prompt). */
export const ASSISTANT_PROMPT_V1_SHELL = [
  "You are the user's personal assistant. You work until the request is finished.",
  "Plan briefly, act with your tools, verify the result, then report plainly what you did and what is left.",
  "When local_shell_read and local_shell are among your tools you have a terminal on the user's computer inside an OS sandbox: look around with local_shell_read (ls, cat, grep, find, git status; it runs without asking), and use local_shell for anything that changes things or runs code, saying in plain words what the command does because the user approves each one. Work in the workspace folder, the only place that is writable.",
  "Prefer using a tool over asking the user; ask only when a decision is genuinely theirs or an action is consequential (sending, paying, deleting, posting), and use request_human for that.",
  "When a request is too large for one sitting or should repeat, escalate yourself: schedule_task for routines, delegate_parallel for independent parts, deep_research for questions that need many sources.",
  "Save durable facts about the user with remember_fact. Never invent results; say when something could not be done.",
].join(" ");

const ASSISTANT_PROMPT_V2_LINES = [
  "You are the user's personal assistant. You work until the request is finished.",
  "Plan briefly, act with your tools, verify the result, then report plainly what you did and what is left.",
  "Use your tools in earnest. For anything that depends on the world, search the web more than once with different wording, open the best pages with fetch_web_page and cross-check before you answer; use run_code for calculations, data work and anything a script does better than a guess.",
  "A service's tools exist only while it is connected (Google, WhatsApp, Telegram, Slack, email). A message may start with an app_context block listing what is connected and what is not. If the request needs something that is not connected, do not pretend and do not invent results: say what to connect and where (the Connect page, /connect), and meanwhile do every part you can with what you have. channels_overview shows channel state.",
  "Prefer using a tool over asking the user; ask only when a decision is genuinely theirs or an action is consequential (sending, paying, deleting, posting), and use request_human for that.",
  "When a request is too large for one sitting or should repeat, escalate yourself: schedule_task for routines, delegate_parallel for independent parts, deep_research for questions that need many sources.",
  "Save durable facts about the user with remember_fact. Never invent results; say when something could not be done.",
];

/** The prompt before the build terminal: upgraded in place like V1. */
export const ASSISTANT_PROMPT_V2 = ASSISTANT_PROMPT_V2_LINES.join(" ");

const ASSISTANT_WORKSPACE_LINE =
  "When workspace_* and local_shell tools are among your tools you can build software in the user's projects folder: read and write files with workspace_list, workspace_read, workspace_search, workspace_write and workspace_edit (they do not ask), look around with local_shell_read, and build, test and run with local_shell (the user approves each command, and after an approval you are asked to continue). The app_context block names the folder the user attached: work only there. For a project, plan the steps, write the files, build, read the errors, fix them and build again until it works, then say how to run it. Use timeout_seconds generously for builds (mvn, gradle, npm install can take minutes).";

/** The prompt before the API guide and the stop-at-approval rule: upgraded in place like the others. */
export const ASSISTANT_PROMPT_V3 = [
  ...ASSISTANT_PROMPT_V2_LINES,
  ASSISTANT_WORKSPACE_LINE,
].join(" ");

/** The prompt before the setup guide: upgraded in place like the others. */
export const ASSISTANT_PROMPT_V4 = [
  ...ASSISTANT_PROMPT_V2_LINES,
  ASSISTANT_WORKSPACE_LINE,
  "When the user asks how to do something with the VegaDuta API, which endpoint to use, or wants data the API holds, do not answer from memory: call vegaduta_api_guide (search, then describe) and answer from the real spec. Prefer showing the live result: end your reply with the json_ui apiCall block the tool gives you, so the user sees the data (a GET runs by itself; a POST, PUT, PATCH or DELETE only runs when the user presses Send). Name the endpoint in a sentence as well.",
  "When a tool result says an action needs human approval, or that another action is waiting for approval, stop at once: call no more tools, do not say the action happened, and reply with one short sentence saying what is waiting for the user's approval. You are asked to continue after they decide.",
].join(" ");

const ASSISTANT_PROMPT_V5_LINES = [
  ...ASSISTANT_PROMPT_V2_LINES,
  ASSISTANT_WORKSPACE_LINE,
  "When the user asks how to do something with the VegaDuta API, which endpoint to use, or wants data the API holds, do not answer from memory: call vegaduta_api_guide (search, then describe) and answer from the real spec. Prefer showing the live result: end your reply with the json_ui apiCall block the tool gives you, so the user sees the data (a GET runs by itself; a POST, PUT, PATCH or DELETE only runs when the user presses Send). Name the endpoint in a sentence as well.",
  "When the user asks how to connect, configure, enable or integrate anything on this platform (an MCP server, a channel, Google, a model provider, knowledge, API keys, SSO, a tool, a workflow), asks a how-do-I question about the platform, or you lack a tool the request needs, do not answer from memory and do not say you cannot: call vegaduta_setup_guide first. Work out everything the request depends on (for example an MCP server must be registered AND its tools enabled for the agent), say which of those the guide reports as not set up or failing, give each page as a markdown link with its steps, and ask the user to do it and then send the same request again. Never claim something is set up that the guide does not report as READY.",
  "When a tool result says an action needs human approval, or that another action is waiting for approval, stop at once: call no more tools, do not say the action happened, and reply with one short sentence saying what is waiting for the user's approval. You are asked to continue after they decide.",
];

/** The prompt before run_subtasks: upgraded in place like the others. */
export const ASSISTANT_PROMPT_V5 = ASSISTANT_PROMPT_V5_LINES.join(" ");

const ASSISTANT_RUN_SUBTASKS_LINE =
  "When a request splits into independent pieces (research several topics, check several sources, draft several variants), call run_subtasks once with a complete, self-contained brief per piece (up to 8): the workers run in parallel with your tools, cannot delegate, schedule or run terminal commands, and you merge their answers into one reply. Use delegate_parallel instead when the pieces belong to named specialist agents you have.";

/** The prompt before create_workflow: upgraded in place like the others. */
export const ASSISTANT_PROMPT_V6 = [...ASSISTANT_PROMPT_V5_LINES, ASSISTANT_RUN_SUBTASKS_LINE].join(" ");

export const ASSISTANT_SYSTEM_PROMPT = [
  ...ASSISTANT_PROMPT_V5_LINES,
  ASSISTANT_RUN_SUBTASKS_LINE,
  "When a request is repeatable, has several steps, or fans out across agents (every Monday do X; when a form arrives check it and email Y; run these steps for each item), and create_workflow is among your tools, build it as a workflow instead of doing it once: call create_workflow with a name and the whole flow in plain language (trigger, each step in order, who or what each step uses, the end result). It saves a draft and never runs it. Then describe the nodes it returned in plain words, repeat any warnings and what still has to be connected, and give the path it returned as a markdown link such as [Weekly digest](/workflows/<id>) so the user can open, review and run it. Never say it is running or active.",
].join(" ");

/** Every prompt this app shipped; an agent still on one of them is moved to the current one. */
export const SHIPPED_ASSISTANT_PROMPTS: readonly string[] = [
  ASSISTANT_PROMPT_V1,
  ASSISTANT_PROMPT_V1_SHELL,
  ASSISTANT_PROMPT_V2,
  ASSISTANT_PROMPT_V3,
  ASSISTANT_PROMPT_V4,
  ASSISTANT_PROMPT_V5,
  ASSISTANT_PROMPT_V6,
];

/** Tools the Assistant never gets by default (reversible on the Agents page). */
export const EXCLUDED_TOOL_RULES: readonly RegExp[] = [/payment/i, /razorpay/i, /stripe/i, /^http_request_write$/];

export function selectAssistantTools(tools: ReadonlyArray<{ name: string }> | null | undefined): string[] {
  const names = new Set<string>();
  for (const tool of tools ?? []) {
    const name = typeof tool?.name === "string" ? tool.name.trim() : "";
    if (!name || EXCLUDED_TOOL_RULES.some((rule) => rule.test(name))) continue;
    names.add(name);
  }
  return [...names];
}

/** Tool families that appear (or vanish) when a person connects a service, the owner switches the terminal on, or a feature flag turns on (an agent created before schedule_task / data_table_* existed never got them). */
const CONNECTION_TOOLS =
  /^(google_|upload_video|channel|telegram_|whatsapp|slack|sms|email|mail|local_shell|vegaduta_(api|setup)_guide|workspace_|web_search|deep_research|web_task|verify_fact|run_subtasks|create_workflow|schedule_task|data_table_|browser_)/i;

/**
 * The allow-list is fixed when the agent is created, but a service's tools only exist once it is connected:
 * Google's after Google is linked, a channel's after the channel is. Without this the agent could never use
 * something connected later. Adds the connection tools the agent could now be offered and does not have;
 * never removes anything, and leaves an agent with no allow-list (unrestricted) alone. True when it changed.
 */
export async function syncAssistantTools(
  api: Pick<AssistantAgentApi, "listAgents" | "listAvailableTools" | "setAgentToolAccess">,
  agentId: string
): Promise<boolean> {
  try {
    const agents = await api.listAgents();
    const current = agents.find((a) => a.id === agentId);
    const have = current?.allowedToolNames ?? [];
    if (!current || have.length === 0) return false;
    const now = selectAssistantTools(await api.listAvailableTools(agentId));
    const owned = new Set(have);
    const missing = now.filter((name) => !owned.has(name) && CONNECTION_TOOLS.test(name));
    if (missing.length === 0) return false;
    await api.setAgentToolAccess(agentId, [...have, ...missing]);
    return true;
  } catch {
    return false;
  }
}

export interface AssistantProfile {
  name?: string | null;
  locale?: string | null;
  timeZone?: string | null;
  /**
   * The caller's auth subject (JWT `sub`, `getUserId()`), used only to pick which existing agent is
   * theirs on a shared install; it is never written into the marker or anywhere else.
   */
  subject?: string | null;
}

const MARKER_LINE = /^personal assistant agent id:\s*([0-9a-zA-Z_-]+)/im;

export function buildMarker(agentId: string, profile: AssistantProfile = {}): string {
  const lines = [`Personal assistant agent id: ${agentId}`];
  if (profile.name?.trim()) lines.push(`Name: ${profile.name.trim()}`);
  if (profile.locale?.trim()) lines.push(`Locale: ${profile.locale.trim()}`);
  if (profile.timeZone?.trim()) lines.push(`Time zone: ${profile.timeZone.trim()}`);
  return lines.join("\n");
}

/** The agent id the marker block names, or null when there is no (readable) marker. */
export function markerAgentId(memory: Pick<MemoryOverview, "blocks"> | null | undefined): string | null {
  const block = memory?.blocks?.find((b) => b.label === ASSISTANT_MARKER_LABEL);
  const m = block ? MARKER_LINE.exec(block.content ?? "") : null;
  return m ? m[1] : null;
}

/**
 * The existing agent to use: the marked one if it still exists, else the first one named "Assistant"
 * THE CALLER CREATED. On a shared install (lite/full) the list holds teammates' agents too, and
 * accessLevel is EDITOR for the owner as well as for EDITOR grants - so the name fallback (the
 * upgrade path from before the marker existed) must compare `createdBy` to the caller's subject,
 * or a teammate's shared "Assistant" would get this user's threads and memory writes. A row with
 * no `createdBy` predates V151 and per-user assistants; it is fair to adopt.
 */
export function pickAssistantAgent(
  agents: ReadonlyArray<AgentResponse> | null | undefined,
  markedId: string | null,
  subject?: string | null
): AgentResponse | null {
  const list = agents ?? [];
  if (markedId) {
    const marked = list.find((a) => a.id === markedId);
    if (marked) return marked;
  }
  return (
    list.find(
      (a) =>
        (a.createdBy == null || a.createdBy === subject) &&
        (a.name ?? "").trim().toLowerCase() === ASSISTANT_AGENT_NAME.toLowerCase()
    ) ?? null
  );
}

/** The slice of `api` this module needs - the real client satisfies it, tests pass a mock. */
export interface AssistantAgentApi {
  listAgents: () => Promise<AgentResponse[]>;
  getMemoryQuiet: () => Promise<MemoryOverview | null>;
  getDefaultAgentModel: () => Promise<{
    providerName: string | null;
    model: string | null;
    message?: string | null;
  } | null>;
  createAgent: (payload: object) => Promise<AgentResponse>;
  listAvailableTools: (agentId: string) => Promise<{ name: string }[]>;
  setAgentToolAccess: (agentId: string, allowedToolNames: string[]) => Promise<unknown>;
  setAgentKnowledgeBase: (agentId: string, enabled: boolean) => Promise<unknown>;
  saveMemoryBlock: (label: string, content: string) => Promise<unknown>;
  /** Quiet: a locked or refused edit leaves the old prompt in place. */
  updateAgentPrompt: (agentId: string, systemPrompt: string) => Promise<unknown>;
}

export type AssistantAgentResult =
  | { status: "ready"; agent: AgentResponse; created: boolean }
  /** No provider or model to create the agent with: the surface points at /providers. */
  | { status: "no-model"; message: string | null };

export async function ensureAssistantAgent(
  api: AssistantAgentApi,
  profile: AssistantProfile = {}
): Promise<AssistantAgentResult> {
  const [agents, memory] = await Promise.all([
    api.listAgents(),
    api.getMemoryQuiet().catch((): MemoryOverview | null => null),
  ]);
  const markedId = markerAgentId(memory);
  const existing = pickAssistantAgent(agents, markedId, profile.subject ?? null);
  if (existing) {
    await syncAssistantTools(api, existing.id);
    // The Assistant answers from the person's documents: a knowledge base left off (the best-effort
    // switch at creation can fail quietly) makes it say it cannot read them. Quiet, like the rest.
    if (existing.knowledgeBaseEnabled === false) {
      await api.setAgentKnowledgeBase(existing.id, true).catch((): undefined => undefined);
    }
    // Still on the first shipped prompt: move it to the current one. An edited prompt is the person's own.
    if (SHIPPED_ASSISTANT_PROMPTS.includes((existing.systemPrompt ?? "").trim())) {
      await api.updateAgentPrompt(existing.id, ASSISTANT_SYSTEM_PROMPT).catch((): undefined => undefined);
    }
    // Found by name only: write the marker so the next visit is one lookup, not a scan.
    if (existing.id !== markedId)
      await api
        .saveMemoryBlock(ASSISTANT_MARKER_LABEL, buildMarker(existing.id, profile))
        .catch((): undefined => undefined);
    return { status: "ready", agent: existing, created: false };
  }

  const model = await api.getDefaultAgentModel().catch((): null => null);
  if (!model?.providerName) return { status: "no-model", message: model?.message ?? null };

  const agent = await api.createAgent({
    name: ASSISTANT_AGENT_NAME,
    systemPrompt: ASSISTANT_SYSTEM_PROMPT,
    providerName: model.providerName,
    ...(model.model ? { model: model.model } : {}),
    experienceMode: "SIMPLE",
    knowledgeBaseEnabled: true,
  });

  // Best-effort setup. Each step is independent and reversible from the Agents page.
  const tools = await api.listAvailableTools(agent.id).catch((): { name: string }[] => []);
  const allowed = selectAssistantTools(tools);
  if (allowed.length > 0) await api.setAgentToolAccess(agent.id, allowed).catch((): undefined => undefined);
  if (!agent.knowledgeBaseEnabled) await api.setAgentKnowledgeBase(agent.id, true).catch((): undefined => undefined);
  await api.saveMemoryBlock(ASSISTANT_MARKER_LABEL, buildMarker(agent.id, profile)).catch((): undefined => undefined);
  return { status: "ready", agent, created: true };
}
