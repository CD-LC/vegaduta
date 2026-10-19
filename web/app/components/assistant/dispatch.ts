import { turnStopKind, type ActivityStep } from "../agents/AgentActivityTrace";

/**
 * The dispatch ladder of the /assistant surface (design doc, "The dispatch ladder"): one cheap,
 * deterministic structural choice per message, after which the Assistant agent does the understanding
 * with its own tools. Pure and framework-free, like start/flow.ts and chatStreamMachine.ts.
 *
 *   0 slash     - a known slash command maps to an existing feature
 *   1 turn      - everything else is one chat turn (POST /api/agents/{assistant}/chat, stepEvents:true)
 *   2 job       - "keep going in the background" (POST /api/agents/{assistant}/jobs)
 *   3 routine   - /schedule <cadence> <prompt> (POST /api/scheduled-tasks)
 *   4 research  - /research <question> (POST /api/research/runs)
 *   5 specialist- /agent <name> <message> (that agent's /chat)
 *
 * Every rung above 1 is behind a server switch; when the switch is off the message degrades to a plain
 * turn (rung 1) with the intent kept in the text, because the agent itself has schedule_task,
 * deep_research and request_human and can still do the right thing. `degradedFrom` lets the surface say
 * so in one quiet line.
 */
export interface DispatchCapabilities {
  /** GET /api/research/status says runs are enabled and search is configured. */
  research: boolean;
  /** GET /api/scheduled-tasks/capabilities says enabled. */
  scheduledTasks: boolean;
  /** Agent jobs exist on every core; false only when the surface could not reach them. */
  jobs: boolean;
}

export const DEFAULT_CAPABILITIES: DispatchCapabilities = { research: false, scheduledTasks: false, jobs: true };

export type ScheduleFrequency = "daily" | "weekdays" | "weekly" | "monthly";

export interface ScheduleSpec {
  frequency: ScheduleFrequency;
  /** HH:mm, 24-hour. */
  time: string;
  /** weekly: MON..SUN; monthly: "1".."28" or "LAST". */
  day?: string;
  prompt: string;
  /** Short name for the routine, taken from the prompt. */
  name: string;
}

export type DegradedFrom = "research" | "schedule" | "background";

export type DispatchPlan =
  | { rung: "empty" }
  | { rung: "turn"; message: string; degradedFrom?: DegradedFrom }
  | { rung: "job"; goal: string }
  | { rung: "research"; question: string }
  | { rung: "schedule"; spec: ScheduleSpec }
  | { rung: "specialist"; agentName: string; message: string }
  | { rung: "command"; command: "new" | "export" | "help" | "remember"; arg: string };

export const SLASH_COMMANDS = [
  "research",
  "schedule",
  "background",
  "agent",
  "remember",
  "export",
  "new",
  "help",
] as const;
export type SlashCommand = (typeof SLASH_COMMANDS)[number];

const KNOWN = new Set<string>(SLASH_COMMANDS);

/** "/research  what is X" -> { command: "research", arg: "what is X" }; null when the text is not a slash command. */
export function parseSlash(input: string): { command: string; arg: string } | null {
  const m = /^\/([a-z][a-z-]*)(?:\s+([\s\S]*))?$/i.exec(input.trim());
  if (!m) return null;
  return { command: m[1].toLowerCase(), arg: (m[2] ?? "").trim() };
}

/** The agent-facing phrasing used when a rung is switched off and the request becomes a plain turn. */
export const DEGRADED_PREFIX: Record<DegradedFrom, string> = {
  research: "Research this thoroughly and cite your sources: ",
  schedule: "Set this up as a recurring task for me: ",
  background: "",
};

export function dispatch(
  input: string,
  caps: DispatchCapabilities = DEFAULT_CAPABILITIES,
  opts: { background?: boolean } = {}
): DispatchPlan {
  const text = input.trim();
  if (!text) return { rung: "empty" };
  const slash = parseSlash(text);
  if (slash && KNOWN.has(slash.command)) {
    const arg = slash.arg;
    switch (slash.command as SlashCommand) {
      case "research":
        if (!arg) return { rung: "command", command: "help", arg: "research" };
        return caps.research
          ? { rung: "research", question: arg }
          : { rung: "turn", message: DEGRADED_PREFIX.research + arg, degradedFrom: "research" };
      case "schedule": {
        if (!arg) return { rung: "command", command: "help", arg: "schedule" };
        const spec = parseSchedule(arg);
        return caps.scheduledTasks && spec
          ? { rung: "schedule", spec }
          : { rung: "turn", message: DEGRADED_PREFIX.schedule + arg, degradedFrom: "schedule" };
      }
      case "background":
        if (!arg) return { rung: "command", command: "help", arg: "background" };
        return caps.jobs ? { rung: "job", goal: arg } : { rung: "turn", message: arg, degradedFrom: "background" };
      case "agent": {
        const m = /^(?:"([^"]+)"|(\S+))\s*([\s\S]*)$/.exec(arg);
        const agentName = (m?.[1] ?? m?.[2] ?? "").trim();
        const message = (m?.[3] ?? "").trim();
        if (!agentName || !message) return { rung: "command", command: "help", arg: "agent" };
        return { rung: "specialist", agentName, message };
      }
      case "remember":
        return arg ? { rung: "command", command: "remember", arg } : { rung: "command", command: "help", arg: "remember" };
      case "export":
      case "new":
      case "help":
        return { rung: "command", command: slash.command as "export" | "new" | "help", arg };
    }
  }
  if (opts.background) {
    return caps.jobs ? { rung: "job", goal: text } : { rung: "turn", message: text, degradedFrom: "background" };
  }
  // An unknown slash ("/foo") is just text the person typed - the agent sees it as written.
  return { rung: "turn", message: text };
}

const DAY_CODES: Record<string, string> = {
  monday: "MON",
  tuesday: "TUE",
  wednesday: "WED",
  thursday: "THU",
  friday: "FRI",
  saturday: "SAT",
  sunday: "SUN",
};

/**
 * The small cadence grammar the composer accepts without a form:
 *   every day | daily | every weekday | weekdays | every week | weekly | every <weekday> |
 *   every month | monthly | monthly on the 15th
 * followed by an optional "at 8", "at 8:30", "at 8pm", "at 18:00" and then the prompt. Anything else
 * returns null and the request goes to the agent as a plain turn (it has the schedule_task tool).
 */
export function parseSchedule(text: string): ScheduleSpec | null {
  const cadenceMatch =
    /^(every\s+day|daily|every\s+weekday|weekdays|every\s+week|weekly|every\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)|every\s+month|monthly(?:\s+on\s+the\s+(\d{1,2}|last)(?:st|nd|rd|th)?)?)(?=\s|$)([\s\S]*)$/i.exec(
      text.trim()
    );
  if (!cadenceMatch) return null;
  const cadence = cadenceMatch[1].toLowerCase().replace(/\s+/g, " ");
  const weekday = cadenceMatch[2]?.toLowerCase();
  const monthDay = cadenceMatch[3]?.toLowerCase();
  let rest = cadenceMatch[4].trim();
  let hour = 9;
  let minute = 0;
  const timeMatch = /^at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?(?=\s|[:,-]|$)/i.exec(rest);
  if (timeMatch) {
    hour = Number(timeMatch[1]);
    minute = timeMatch[2] != null ? Number(timeMatch[2]) : 0;
    const meridiem = timeMatch[3]?.toLowerCase();
    if (meridiem === "pm" && hour < 12) hour += 12;
    if (meridiem === "am" && hour === 12) hour = 0;
    if (hour > 23 || minute > 59) return null;
    rest = rest.slice(timeMatch[0].length);
  }
  const prompt = rest.replace(/^\s*[:,-]\s*/, "").trim();
  if (!prompt) return null;
  const time = `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
  let frequency: ScheduleFrequency;
  let day: string | undefined;
  if (weekday) {
    frequency = "weekly";
    day = DAY_CODES[weekday];
  } else if (cadence === "every day" || cadence === "daily") frequency = "daily";
  else if (cadence === "every weekday" || cadence === "weekdays") frequency = "weekdays";
  else if (cadence === "every week" || cadence === "weekly") {
    frequency = "weekly";
    day = "MON";
  } else {
    frequency = "monthly";
    if (monthDay === "last") day = "LAST";
    else {
      const n = monthDay ? Number(monthDay) : 1;
      if (n < 1 || n > 28) return null;
      day = String(n);
    }
  }
  return { frequency, time, day, prompt, name: scheduleName(prompt) };
}

/** A routine's name: the prompt's first line, cut at a word boundary. */
export function scheduleName(prompt: string, max = 60): string {
  const line = prompt.split(/\r?\n/)[0].trim();
  if (line.length <= max) return line;
  const cut = line.slice(0, max);
  const space = cut.lastIndexOf(" ");
  return `${space > max / 2 ? cut.slice(0, space) : cut}…`;
}

/** The step cap for one chat turn was hit: the rest of the work belongs in a background job (rung 2). */
export function needsBackground(steps: readonly ActivityStep[] | null | undefined): boolean {
  return turnStopKind(steps) === "step_limit";
}

/** AgentJobStartRequest.goal is capped at 8000 characters server-side. */
export const JOB_GOAL_MAX_CHARS = 8000;

/**
 * The goal of a background job that continues a thread: the request, with as much of the conversation
 * so far as fits (newest first when cutting), so the job knows what was already said and done.
 */
export function buildJobGoal(
  thread: ReadonlyArray<{ role: "user" | "assistant"; content: string }>,
  request: string,
  max = JOB_GOAL_MAX_CHARS
): string {
  const ask = request.trim();
  if (thread.length === 0) return ask.slice(0, max);
  const head = "Continue this conversation and finish the request at the end.\n\nConversation so far:\n";
  const tail = `\n\nRequest:\n${ask}`;
  let budget = max - head.length - tail.length;
  if (budget <= 0) return ask.slice(0, max);
  const lines: string[] = [];
  for (let i = thread.length - 1; i >= 0; i--) {
    const entry = thread[i];
    if (!entry.content?.trim()) continue;
    const line = `${entry.role === "user" ? "User" : "Assistant"}: ${entry.content.trim()}`;
    if (line.length + 1 > budget) break;
    lines.unshift(line);
    budget -= line.length + 1;
  }
  return `${head}${lines.join("\n")}${tail}`;
}
