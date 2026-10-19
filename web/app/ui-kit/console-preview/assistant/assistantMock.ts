// Sample answers for the /assistant preview (dev only). Layered over the dashboard-preview mock, which
// answers everything else: this file owns the per-user Assistant agent, its streamed chat turn (an SSE
// body with step events, so WorkedForBlock has something to fold), a stored transcript, and the rungs
// above a plain turn (job, research run, routine). Installed once at module evaluation by the preview page.
import { API_BASE } from "../../../lib/api";
import { STEP_EVENT_MARKER } from "../../../lib/api/streamMarkers";
import { ASSISTANT_MARKER_LABEL } from "../../../components/assistant/assistantAgent";
import { agents } from "../../dashboard-preview/mock-sample-data";

const INSTALLED_FLAG = "__vdAssistantPreviewMock";
export const PREVIEW_ASSISTANT_ID = "agt_assistant_preview";
const SESSION_ID = "sess-preview-dentist";

const assistantAgent = {
  id: PREVIEW_ASSISTANT_ID,
  name: "Assistant",
  description: "Your personal assistant (sample).",
  providerName: "OLLAMA",
  model: "qwen3.5:4b",
  status: "ACTIVE",
  systemPrompt: "You are the user's personal assistant.",
  knowledgeBaseEnabled: true,
  experienceMode: "SIMPLE",
  modalities: { image: true, audio: false, video: false, document: true, sandboxData: false, maxAttachmentBytes: 10_000_000, maxAttachments: 3 },
  createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
  updatedAt: new Date().toISOString(),
};

const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });

const step = (event: Record<string, unknown>) => `data:${STEP_EVENT_MARKER}${JSON.stringify(event)}\n\n`;
const text = (chunk: string) => `data:${chunk.replace(/\n/g, "\ndata:")}\n\n`;

type Frames = Array<[number, string]>;

/** Which connections the sample install has; the connect-first flow reads these through /api/channels/status. */
const connected = { whatsapp: false, telegram: false, email: true, google: false };
if (typeof window !== "undefined") {
  (window as unknown as Record<string, unknown>).__vdMockConnections = {
    set: (key: keyof typeof connected, on: boolean) => {
      connected[key] = on;
    },
    get: () => ({ ...connected }),
  };
}
const channelRow = (key: string, label: string, on: boolean) => ({ key, label, state: on ? "CONNECTED" : "NOT_CONNECTED", detail: on ? "Connected" : "Not connected", sessionCount: on ? 3 : 0 });

/** Several searches, a page read and a calculation: what a research-style request looks like. */
function searchFrames(message: string): Frames {
  return [
    [0, step({ type: "run_started", sessionId: SESSION_ID, agentId: PREVIEW_ASSISTANT_ID })],
    [200, step({ type: "plan", text: "Search from three angles, read the best pages, check the numbers, then answer." })],
    [300, step({ type: "tool_call", id: 1, tool: "web_search", args: JSON.stringify({ query: message.slice(0, 60) }) })],
    [900, step({ type: "tool_result", id: 1, tool: "web_search", preview: "8 results", ms: 600 })],
    [1000, step({ type: "tool_call", id: 2, tool: "web_search", args: JSON.stringify({ query: `${message.slice(0, 40)} review comparison 2026` }) })],
    [1600, step({ type: "tool_result", id: 2, tool: "web_search", preview: "6 results", ms: 600 })],
    [1700, step({ type: "tool_call", id: 3, tool: "fetch_web_page", args: JSON.stringify({ url: "https://example.test/comparison" }) })],
    [2400, step({ type: "tool_result", id: 3, tool: "fetch_web_page", preview: "Read 4,200 words", ms: 700 })],
    [2500, step({ type: "tool_call", id: 4, tool: "run_code", args: JSON.stringify({ language: "python", code: "print(sum(prices)/len(prices))" }) })],
    [3000, step({ type: "tool_result", id: 4, tool: "run_code", preview: "average 412.5", ms: 480 })],
    [3100, text("I searched twice with different wording, read the comparison page and checked the average: **412.5**. ")],
    [3400, text("The two sources agree on the ranking; the cheapest is not the best value.")],
    [3500, step({ type: "run_finished", ms: 3500 })],
  ];
}

/** Channel work: check the channel, read it, summarise. Only reached once WhatsApp is connected. */
function channelFrames(): Frames {
  return [
    [0, step({ type: "run_started", sessionId: SESSION_ID, agentId: PREVIEW_ASSISTANT_ID })],
    [200, step({ type: "tool_call", id: 1, tool: "channels_overview", args: "{}" })],
    [700, step({ type: "tool_result", id: 1, tool: "channels_overview", preview: "whatsapp CONNECTED, email CONNECTED", ms: 300 })],
    [800, step({ type: "tool_call", id: 2, tool: "channel_read_messages", args: JSON.stringify({ channel: "whatsapp", limit: 20 }) })],
    [1500, step({ type: "tool_result", id: 2, tool: "channel_read_messages", preview: "12 messages from 4 conversations", ms: 700 })],
    [1600, text("You have **12 WhatsApp messages** from 4 people today. Two need an answer: Asha asks about Saturday, and the plumber confirmed 4 pm.")],
    [1900, step({ type: "run_finished", ms: 1900 })],
  ];
}

/** The terminal: look around without asking, then a command that waits for the person (sample trace only). */
function shellFrames(): Frames {
  return [
    [0, step({ type: "run_started", sessionId: SESSION_ID, agentId: PREVIEW_ASSISTANT_ID })],
    [200, step({ type: "tool_call", id: 1, tool: "local_shell_read", args: JSON.stringify({ command: "ls -la ~/Downloads | head -20" }) })],
    [700, step({ type: "tool_result", id: 1, tool: "local_shell_read", preview: "exit 0 - 14 entries, 3 larger than 500 MB", ms: 300 })],
    [800, step({ type: "tool_call", id: 2, tool: "local_shell_read", args: JSON.stringify({ command: "du -sh ~/Downloads/* | sort -h | tail -3" }) })],
    [1300, step({ type: "tool_result", id: 2, tool: "local_shell_read", preview: "exit 0 - 3 lines", ms: 400 })],
    [1400, text("Your Downloads folder holds **14 items**; three are over 500 MB (two disk images and a video). ")],
    [1700, text("I can move them into `vegaduta-workspace/old-downloads` if you want. That is a command, so you would approve it first.")],
    [1900, step({ type: "run_finished", ms: 1900 })],
  ];
}

/** The scripted turn: a plan, two tool calls (one fails), one skipped call, then the answer. */
function chatStream(rawMessage: string): Response {
  const message = rawMessage.replace(/^<app_context>[\s\S]*?<\/app_context>\s*/, "");
  if (/whats\s?app/i.test(message) && connected.whatsapp) return streamOf(channelFrames());
  if (/\b(terminal|command|folder|downloads|disk|files?)\b/i.test(message)) return streamOf(shellFrames());
  if (!/calendar|dentist|book/i.test(message)) return streamOf(searchFrames(message));
  const frames: Frames = [
    [0, step({ type: "run_started", sessionId: SESSION_ID, agentId: PREVIEW_ASSISTANT_ID })],
    [200, step({ type: "memory", blocks: 1, blockLabels: ["user_profile"], privateFacts: 2, sharedFacts: 0 })],
    [300, step({ type: "plan", text: `Find options for "${message.slice(0, 40)}", check the calendar, then book.` })],
    [400, step({ type: "tool_call", id: 1, tool: "web_search", args: JSON.stringify({ query: "dentists near Indiranagar open next week" }) })],
    [1400, step({ type: "tool_result", id: 1, tool: "web_search", preview: "3 results: Smile Studio, Dr Rao Dental, Apollo Dental", ms: 980 })],
    [1600, step({ type: "tool_call", id: 2, tool: "browser_navigate", args: JSON.stringify({ url: "https://example.test/book" }) })],
    [2600, step({ type: "error", id: 2, tool: "browser_navigate", message: "Navigation timed out after 10 s", ms: 1000 })],
    [2700, step({ type: "guardrail", tool: "channel_send_message", reason: "needs your approval before anything leaves this machine" })],
    [2800, step({ type: "tool_call", id: 3, tool: "google_calendar", args: JSON.stringify({ action: "create", title: "Dentist", when: "Tue 10:30" }) })],
    [3600, step({ type: "tool_result", id: 3, tool: "google_calendar", preview: "Created event 'Dentist' on Tue 10:30", ms: 800 })],
    [3700, text("Booked **Tue 10:30** at Smile Studio and added it to your calendar.")],
    [4000, text("\n\nThe booking site's own page timed out, so I confirmed by phone number from the listing instead. ")],
    [4300, text("Want a reminder the day before?\n\n")],
    [4400, text("```json_ui\n" + JSON.stringify({ version: "v1", root: { type: "row", children: [{ type: "button", props: { label: "Yes, 9:00" }, action: { type: "sendMessage", message: "Yes, remind me at 9:00 the day before" } }, { type: "button", props: { label: "No", tone: "secondary" }, action: { type: "sendMessage", message: "No reminder needed" } }] } }) + "\n```")],
    [4500, step({ type: "run_finished", ms: 4500 })],
  ];
  return streamOf(frames);
}

function streamOf(frames: Frames): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      let elapsed = 0;
      for (const [at, frame] of frames) {
        elapsed = at;
        setTimeout(() => controller.enqueue(encoder.encode(frame)), at);
      }
      setTimeout(() => controller.close(), elapsed + 50);
    },
  });
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "text/event-stream", "X-Session-Id": SESSION_ID },
  });
}

const transcript = {
  sessionId: SESSION_ID,
  messages: [
    { role: "user", content: "book me a dentist next week and put it in my calendar", timestamp: new Date(Date.now() - 3_600_000).toISOString(), status: "ok", error: false },
    { role: "assistant", content: "Booked **Tue 10:30** at Smile Studio and added it to your calendar.", timestamp: new Date(Date.now() - 3_500_000).toISOString(), status: "ok", error: false },
  ],
};

const liveJobs = [
  { id: "job_preview_1", agentId: PREVIEW_ASSISTANT_ID, agentName: "Assistant", status: "RUNNING", goalPreview: "Compare the three dentists' reviews and pick the best", progressNote: "Reading reviews (2 of 3)", createdAt: new Date(Date.now() - 130_000).toISOString(), startedAt: new Date(Date.now() - 120_000).toISOString(), finishedAt: null as string | null, errorCode: null as string | null, parkedAt: null as string | null, approvalDeadlineAt: null as string | null },
];

type Route = [RegExp, (m: RegExpMatchArray, url: URL, init?: RequestInit) => Response | null];

const routes: Route[] = [
  [/^\/api\/agents$/, (_m, _u, init) => ((init?.method || "GET").toUpperCase() === "GET" ? json([assistantAgent, ...agents]) : null)],
  [/^\/api\/memory$/, () => json({ available: true, blocksEnabled: true, limits: { maxCharsPerBlock: 2000, maxProjectBlocks: 10, maxFactChars: 500 }, blocks: [{ label: ASSISTANT_MARKER_LABEL, content: `Personal assistant agent id: ${PREVIEW_ASSISTANT_ID}\nName: Priya` }], facts: [] })],
  [/^\/api\/memory\/facts$/, () => json({ id: "fact_preview", scope: "shared", content: "remembered (sample)" })],
  [/^\/api\/agents\/[^/]+\/chat$/, (_m, _u, init) => chatStream(bodyMessage(init))],
  [/^\/api\/agents\/[^/]+\/chat\/sessions\/[^/]+\/cancel$/, () => json(null, 204)],
  [/^\/api\/agents\/[^/]+\/sessions\/[^/]+\/transcript$/, () => json(transcript)],
  [/^\/api\/agents\/[^/]+\/jobs$/, (_m, _u, init) => ((init?.method || "GET").toUpperCase() === "POST" ? json({ id: "job_preview_2", agentId: PREVIEW_ASSISTANT_ID, status: "QUEUED", goal: bodyField(init, "goal") }, 202) : json([]))],
  [/^\/api\/agent-jobs$/, () => json(liveJobs)],
  [/^\/api\/research\/status$/, () => json({ enabled: true, searchConfigured: true, runsEnabled: true, limits: { maxSearchesPerRun: 20, maxFetchesPerRun: 20, maxSources: 12, totalDeadlineSeconds: 600, runBudgetUsd: 0, maxRunsPerUserPerDay: 5, runTimeoutSeconds: 900 }, remainingToday: 4 })],
  [/^\/api\/research\/runs$/, () => json({ id: "run_preview_1", agentId: "agt_research_preview", status: "QUEUED" }, 202)],
  [/^\/api\/scheduled-tasks\/capabilities$/, () => json({ enabled: true, maxTasks: 10, minIntervalMinutes: 15, maxPromptChars: 2000, deliveryChannels: ["in_app", "email"] })],
  [/^\/api\/scheduled-tasks\/preview$/, () => json({ cron: "0 8 * * MON-FRI", description: "Every weekday at 08:00", timezone: "Asia/Kolkata", nextRuns: [new Date(Date.now() + 86_400_000).toISOString()] })],
  [/^\/api\/scheduled-tasks$/, (_m, _u, init) => ((init?.method || "GET").toUpperCase() === "POST" ? json({ id: "task_preview_1", agentId: PREVIEW_ASSISTANT_ID, name: bodyField(init, "name"), status: "ACTIVE" }, 201) : json([]))],
  [/^\/api\/channels\/status$/, () => json({ channels: [channelRow("telegram", "Telegram", connected.telegram), channelRow("whatsapp", "WhatsApp", connected.whatsapp), channelRow("email", "Email", connected.email)] })],
  [/^\/api\/channels\/google\/status$/, () => (connected.google ? json({ connected: true, email: "priya@example.test" }) : json(null, 204))],
  [/^\/api\/agents\/[^/]+\/available-tools$/, () => json([{ name: "web_search" }, { name: "fetch_web_page" }, { name: "run_code" }, { name: "remember_fact" }])],
  [/^\/api\/chat-import\/features$/, () => json({ exportEnabled: true, importEnabled: false })],
  [/^\/api\/agents\/[^/]+\/sessions\/[^/]+\/export$/, () => new Response("# Thread (sample)\n", { status: 200, headers: { "Content-Type": "text/markdown", "Content-Disposition": 'attachment; filename="thread-sample.md"' } })],
];

function parseBody(init?: RequestInit): Record<string, unknown> {
  try {
    return typeof init?.body === "string" ? (JSON.parse(init.body) as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}
const bodyMessage = (init?: RequestInit) => String(parseBody(init).message ?? "");
const bodyField = (init: RequestInit | undefined, key: string) => String(parseBody(init)[key] ?? "");

/** Wraps window.fetch on top of installMockApi(); anything not listed above falls through to it. */
export function installAssistantMock(): boolean {
  if (process.env.NODE_ENV === "production") return false;
  if (typeof window === "undefined" || typeof window.fetch !== "function") return false;
  const w = window as unknown as Record<string, unknown>;
  if (w[INSTALLED_FLAG]) return true;
  const inner = window.fetch.bind(window);
  const base = API_BASE.replace(/\/+$/, "");
  window.fetch = ((input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const href = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    if (!href.startsWith(base + "/")) return inner(input, init);
    let url: URL;
    try {
      url = new URL(href);
    } catch {
      return inner(input, init);
    }
    for (const [pattern, handler] of routes) {
      const match = url.pathname.match(pattern);
      if (!match) continue;
      const res = handler(match, url, init);
      if (res) return Promise.resolve(res);
    }
    return inner(input, init);
  }) as typeof window.fetch;
  w[INSTALLED_FLAG] = true;
  return true;
}
