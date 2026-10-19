// "An Impossible Day" - the script.
//
// One small-business owner, one night and one day, told as a time-lapse. She is
// asleep for the first half of it. Eight agents work the business around her; two
// things genuinely need her, and the agents know which. It is a SAMPLE day and is
// labelled as one everywhere it is shown: every mechanism in it is a real
// platform feature (each event names which, and whether it runs in the /try
// sandbox or needs a workspace), but the customer, the invoices and the counts are
// invented for the story. The counters are derived from this script - they are the
// film's own arithmetic, not a claim about anyone's results.

export type AgentId = "desk" | "watch" | "brief" | "chase" | "legal" | "hire" | "ops" | "guard";

export type DayAgent = { id: AgentId; name: string; role: string; angle: number };

/** Eight agents on a ring, clockwise from the top. */
export const DAY_AGENTS: DayAgent[] = [
  { id: "desk", name: "Customer desk", role: "Answers every message", angle: -90 },
  { id: "watch", name: "Price watcher", role: "Watches competitors", angle: -45 },
  { id: "brief", name: "Reporter", role: "Briefs and reports", angle: 0 },
  { id: "chase", name: "Invoice chaser", role: "Follows up on money", angle: 45 },
  { id: "legal", name: "Contract reader", role: "Reads the fine print", angle: 90 },
  { id: "hire", name: "Recruiter", role: "Screens candidates", angle: 135 },
  { id: "ops", name: "Ops", role: "Books and schedules", angle: 180 },
  { id: "guard", name: "Guardian", role: "Stops mistakes", angle: -135 },
];

export type PhoneItem = {
  /** Progress inside the event at which this appears. */
  at: number;
  kind: "in" | "out" | "note" | "line" | "push" | "approved" | "alert" | "ok";
  text: string;
  sub?: string;
};

export type PhoneScreen = { title: string; sub: string; tone: "green" | "sky" | "violet" | "amber" | "rose"; items: PhoneItem[] };

export type Stats = { msgs?: number; tools?: number; docs?: number; asks?: number; blocked?: number };

export type DayEvent = {
  id: string;
  agent: AgentId;
  /** Where the work arrives from / goes to, shown on the outer ring. */
  port: { label: string; icon: "whatsapp" | "web" | "telegram" | "email" | "docs" | "calendar" | "agent" };
  /** Clock time shown when the event starts, and the clock hours it spans. */
  time: string;
  h0: number;
  h1: number;
  ms: number;
  short: string;
  title: string;
  caption: string;
  tools: { label: string; result: string }[];
  /** Needs the owner: the run pauses on her phone. */
  approval?: boolean;
  /** Agent-to-agent handoff: the packet starts at another agent. */
  from?: AgentId;
  where: "sandbox" | "workspace";
  explain: { feature: string; where: string }[];
  phone: PhoneScreen;
  stats: Stats;
  /** The brief the "Build this one" button drops into the studio launcher. */
  build: string;
};

export const DAY_EVENTS: DayEvent[] = [
  {
    id: "desk", agent: "desk", port: { label: "WhatsApp", icon: "whatsapp" }, time: "02:07", h0: 1.5, h1: 3, ms: 9000, short: "02:07",
    title: "A customer writes in Tamil at 2 a.m.",
    caption: "It is 2:07. A customer asks about a saree in Tamil. The agent searches Meera's price list and answers in Tamil, in seconds. Meera never wakes.",
    tools: [
      { label: "Search your documents", result: "price list: blue in stock" },
      { label: "Reply in the customer's language", result: "sent on WhatsApp" },
    ],
    where: "sandbox",
    explain: [
      { feature: "Channels", where: "WhatsApp, Telegram and Email work out of the box; six more with your own credentials" },
      { feature: "Knowledge", where: "Answers come from your own documents, and name the passage" },
    ],
    phone: {
      title: "WhatsApp", sub: "a customer", tone: "green",
      items: [
        { at: 0.06, kind: "in", text: "இந்த புடவை நீல நிறத்தில் இருக்கிறதா? மதுரைக்கு டெலிவரி?", sub: "(Is this saree available in blue? Delivery to Madurai?)" },
        { at: 0.7, kind: "out", text: "ஆம், நீல நிறம் கையிருப்பில் உள்ளது. மதுரைக்கு மூன்று நாட்களில் டெலிவரி.", sub: "(Yes, blue is in stock. Delivery to Madurai in three days.)" },
      ],
    },
    stats: { msgs: 1, tools: 2, docs: 1 },
    build: "Answer customer messages for my small shop from my price list and delivery rules. Reply in the customer's own language. If someone wants a refund or a discount, do not decide - summarise and hand it to me.",
  },
  {
    id: "watch", agent: "watch", port: { label: "Competitor sites", icon: "web" }, time: "04:30", h0: 3, h1: 5.5, ms: 9000, short: "04:30",
    title: "It reads three competitor pages",
    caption: "4:30. The price watcher opens three competitor pages, compares them with yesterday in real code, and notes what moved.",
    tools: [
      { label: "Read a web page", result: "three pages read" },
      { label: "Run code", result: "compared with yesterday: two prices dropped" },
    ],
    where: "sandbox",
    explain: [
      { feature: "Live tools", where: "Page reading and code execution run for real in the /try sandbox" },
      { feature: "Schedules", where: "Running this every night on its own is a workspace feature" },
    ],
    phone: {
      title: "Price watcher", sub: "notes", tone: "sky",
      items: [
        { at: 0.3, kind: "line", text: "Page 1 read", sub: "competitor A" },
        { at: 0.42, kind: "line", text: "Page 2 read", sub: "competitor B" },
        { at: 0.54, kind: "line", text: "Page 3 read", sub: "competitor C" },
        { at: 0.75, kind: "note", text: "Two competitors lowered prices overnight.", sub: "Saved for the morning brief." },
      ],
    },
    stats: { tools: 4, docs: 3 },
    build: "Check three competitor websites I give you and tell me which prices or offers changed, with the page each came from.",
  },
  {
    id: "brief", agent: "brief", from: "watch", port: { label: "Telegram", icon: "telegram" }, time: "06:45", h0: 5.5, h1: 7.5, ms: 8000, short: "06:45",
    title: "One agent briefs another - then writes to her",
    caption: "6:45. The reporter asks the price watcher what it found - agent to agent - and writes Meera's morning brief on Telegram.",
    tools: [
      { label: "Ask the Price watcher (agent to agent)", result: "two price drops" },
      { label: "Write the morning brief", result: "posted to Telegram" },
    ],
    where: "sandbox",
    explain: [
      { feature: "Agent-to-agent", where: "Agents call other agents through the A2A protocol, across teams" },
      { feature: "Telegram", where: "Pair a bot in the Deploy stage" },
    ],
    phone: {
      title: "Telegram", sub: "Morning brief", tone: "sky",
      items: [
        { at: 0.5, kind: "out", text: "Good morning, Meera.", sub: "Your brief" },
        { at: 0.62, kind: "line", text: "Two competitors cut prices overnight." },
        { at: 0.72, kind: "line", text: "One customer asked about blue sarees - answered in Tamil." },
        { at: 0.82, kind: "ok", text: "Nothing needs you yet." },
      ],
    },
    stats: { tools: 2, msgs: 1 },
    build: "Every morning, write me a short brief of what happened overnight - questions answered, prices that moved, and anything that needs me. Keep it to five lines.",
  },
  {
    id: "chase", agent: "chase", port: { label: "Invoices", icon: "docs" }, time: "08:15", h0: 7.5, h1: 9.5, ms: 11000, short: "08:15", approval: true,
    title: "It needs her - and asks first",
    caption: "8:15. The invoice chaser finds four overdue invoices and drafts four polite reminders. Then it stops. Nothing goes out until Meera says yes.",
    tools: [
      { label: "Search your documents", result: "invoices: four overdue" },
      { label: "Run code", result: "dates checked" },
      { label: "Draft four reminders", result: "waiting for approval" },
    ],
    where: "sandbox",
    explain: [
      { feature: "Approval step", where: "A workflow node that really pauses the run until a human answers" },
      { feature: "Audit trail", where: "Who approved what, and when, is logged" },
    ],
    phone: {
      title: "Meera's phone", sub: "approval", tone: "amber",
      items: [
        { at: 0.62, kind: "push", text: "Invoice chaser needs your OK", sub: "Send four polite reminders to overdue customers?" },
        { at: 0.84, kind: "approved", text: "Approved by Meera - logged" },
        { at: 0.94, kind: "ok", text: "Four reminders sent by email." },
      ],
    },
    stats: { asks: 1, tools: 3, docs: 1, msgs: 4 },
    build: "Every Monday, list my unpaid invoices older than 15 days and draft a polite reminder for each customer, in the customer's language. Never send anything without my approval.",
  },
  {
    id: "legal", agent: "legal", port: { label: "Supplier contract", icon: "docs" }, time: "10:00", h0: 9.5, h1: 11.5, ms: 8000, short: "10:00",
    title: "It reads the contract nobody reads",
    caption: "10:00. A supplier sends a long contract. The agent reads every page - scans included - and flags the two clauses that could cost Meera money.",
    tools: [
      { label: "Read a document (with OCR)", result: "all pages read" },
      { label: "Search your documents", result: "two clauses flagged" },
    ],
    where: "workspace",
    explain: [
      { feature: "Documents", where: "Text is searched in /try; PDF, Word and scanned pages (OCR) are workspace features" },
      { feature: "Citations", where: "Every flag quotes the clause it came from" },
    ],
    phone: {
      title: "Contract review", sub: "flags", tone: "violet",
      items: [
        { at: 0.5, kind: "alert", text: "Auto-renews for a full year unless cancelled early", sub: "Clause quoted from the contract" },
        { at: 0.7, kind: "alert", text: "A penalty applies if you leave early", sub: "Clause quoted from the contract" },
      ],
    },
    stats: { docs: 1, tools: 2 },
    build: "Explain my rental agreement in plain English, and flag any clause that could cost me money or that is unusual. Quote the clause each time.",
  },
  {
    id: "hire", agent: "hire", port: { label: "Applications", icon: "email" }, time: "13:00", h0: 11.5, h1: 14.5, ms: 8000, short: "13:00", approval: true,
    title: "It ranks the candidates - she picks",
    caption: "1 p.m. A stack of CVs arrives. The recruiter ranks them against the job description and asks before it contacts a single person.",
    tools: [
      { label: "Read the CVs", result: "all read" },
      { label: "Rank against the job description", result: "top three, with reasons" },
    ],
    where: "workspace",
    explain: [
      { feature: "Human in the loop", where: "Anything that reaches a person can wait for your approval" },
      { feature: "Reasoning shown", where: "One honest line on why each candidate made the list" },
    ],
    phone: {
      title: "Meera's phone", sub: "approval", tone: "amber",
      items: [
        { at: 0.6, kind: "push", text: "Recruiter needs your OK", sub: "Invite the top three candidates to interview?" },
        { at: 0.84, kind: "approved", text: "Approved by Meera - logged" },
        { at: 0.94, kind: "ok", text: "Three invitations sent." },
      ],
    },
    stats: { asks: 1, docs: 1, tools: 2, msgs: 3 },
    build: "Read the CVs I give you against this job description and rank the top five, with one honest line on why each made the list and one concern.",
  },
  {
    id: "ops", agent: "ops", port: { label: "Calendar", icon: "calendar" }, time: "16:20", h0: 14.5, h1: 17.5, ms: 8000, short: "16:20",
    title: "It books the supplier call",
    caption: "4:20. The supplier asks for a call. Ops checks the calendar through an MCP connector, finds a free slot, and books it.",
    tools: [
      { label: "Calendar (MCP): find a free slot", result: "Thursday 11:00 is free" },
      { label: "Calendar (MCP): book the call", result: "booked" },
    ],
    where: "workspace",
    explain: [
      { feature: "MCP registry", where: "Search published MCP servers in /try; connect them to agents in a workspace" },
      { feature: "Any system", where: "If it has an MCP server, an agent can use it" },
    ],
    phone: {
      title: "Calendar", sub: "this week", tone: "sky",
      items: [
        { at: 0.3, kind: "line", text: "Wed 15:00", sub: "Fabric delivery" },
        { at: 0.55, kind: "line", text: "Thu 09:30", sub: "Shop opening" },
        { at: 0.78, kind: "ok", text: "Thu 11:00 - Supplier call", sub: "Booked by Ops" },
      ],
    },
    stats: { tools: 2, msgs: 1 },
    build: "When a supplier asks for a meeting, check my calendar, find a free slot and propose it. Never book anything without telling me.",
  },
  {
    id: "guard", agent: "guard", port: { label: "Outgoing email", icon: "email" }, time: "19:00", h0: 17.5, h1: 20, ms: 9000, short: "19:00",
    title: "It stops a mistake before it happens",
    caption: "7 p.m. A draft reply is about to leave with a customer's ID number in it. The guardrail catches it, blocks the send, and redacts it.",
    tools: [
      { label: "Guardrail: personal-data check", result: "BLOCKED - ID number in draft" },
      { label: "Redact and re-check", result: "clean - sent" },
    ],
    where: "sandbox",
    explain: [
      { feature: "Guardrails", where: "Personal data, tone, policy and jailbreak checks run on every turn" },
      { feature: "Always on", where: "The studio's own safety rules stay on and cannot be removed" },
    ],
    phone: {
      title: "Outgoing email", sub: "guardrail", tone: "rose",
      items: [
        { at: 0.28, kind: "line", text: "Draft reply to a customer", sub: "…your ID number is 4821 …" },
        { at: 0.5, kind: "alert", text: "Blocked before sending", sub: "Personal data found in the draft" },
        { at: 0.75, kind: "ok", text: "Redacted copy sent instead", sub: "ID number removed" },
      ],
    },
    stats: { blocked: 1, tools: 2, msgs: 1 },
    build: "Draft replies to my customers, but never include personal identifiers such as ID numbers, phone numbers or addresses. If a draft contains any, redact them and tell me.",
  },
  {
    id: "report", agent: "brief", port: { label: "Telegram", icon: "telegram" }, time: "22:00", h0: 20, h1: 23, ms: 8000, short: "22:00",
    title: "It writes the day up - honestly",
    caption: "10 p.m. The reporter reads the day's activity log and tells Meera exactly what happened - including the two things that needed her.",
    tools: [
      { label: "Collect the day's activity log", result: "every action, with who approved it" },
      { label: "Write the evening report", result: "posted to Telegram" },
    ],
    where: "sandbox",
    explain: [
      { feature: "Audit trail", where: "Every consequential action is logged and reportable" },
      { feature: "Reports", where: "The same agent, on a schedule, in a workspace" },
    ],
    phone: { title: "Telegram", sub: "Evening report", tone: "sky", items: [] },
    stats: { tools: 2, msgs: 1 },
    build: "Every evening, write me a short report of what my agents did today - what they handled alone, what needed my approval, and anything they stopped.",
  },
];

export type DayChapter = {
  id: string;
  short: string;
  kicker: string;
  title: string;
  caption: string;
  start: number;
  ms: number;
  h0: number;
  h1: number;
  event: DayEvent | null;
  explain: { feature: string; where: string }[];
  build: string | null;
};

const INTRO_MS = 6000;
const FINALE_MS = 10000;

export const DAY_CHAPTERS: DayChapter[] = (() => {
  const out: DayChapter[] = [];
  let at = 0;
  out.push({
    id: "midnight", short: "00:00", kicker: "A sample day", title: "Meera runs a shop. Her agents don't sleep.", start: at, ms: INTRO_MS, h0: 0, h1: 1.5, event: null, build: null,
    caption: "Meera runs a small textile business. She is asleep. Eight agents are not. Watch one night and one day.",
    explain: [{ feature: "A sample day", where: "Invented for the story - but every mechanism in it is a real feature, tagged where you can try it" }],
  });
  at += INTRO_MS;
  for (const e of DAY_EVENTS) {
    out.push({
      id: e.id, short: e.short, kicker: e.time, title: e.title, start: at, ms: e.ms, h0: e.h0, h1: e.h1, event: e, build: e.build,
      caption: e.caption,
      explain: [{ feature: e.where === "sandbox" ? "Try it in /try" : "In a free workspace", where: e.where === "sandbox" ? "Runs in the sandbox today" : "Needs a workspace - your agent moves in when you sign up" }, ...e.explain],
    });
    at += e.ms;
  }
  out.push({
    id: "finale", short: "Wake", kicker: "24:00", title: "Meera wakes to a finished day.", start: at, ms: FINALE_MS, h0: 23, h1: 24, event: null, build: null,
    caption: "Most of it never needed her. Two things did - and the agents knew which. What is YOUR impossible job?",
    explain: [{ feature: "Your turn", where: "Describe any job - it becomes a real agent in the studio, no signup" }],
  });
  return out;
})();

export const DAY_TOTAL_MS = DAY_CHAPTERS.reduce((n, c) => n + c.ms, 0);

export function locateDay(t: number): { index: number; p: number; hour: number } {
  const tt = Math.min(Math.max(t, 0), DAY_TOTAL_MS - 1);
  let index = 0;
  for (let i = 0; i < DAY_CHAPTERS.length; i++) if (tt >= DAY_CHAPTERS[i].start) index = i;
  const c = DAY_CHAPTERS[index];
  const p = (tt - c.start) / c.ms;
  return { index, p, hour: c.h0 + (c.h1 - c.h0) * p };
}

/** What the film has added up by chapter `index` at progress p (the event's own numbers land at 70% through it). */
export function statsAt(index: number, p: number): Required<Stats> {
  const total: Required<Stats> = { msgs: 0, tools: 0, docs: 0, asks: 0, blocked: 0 };
  DAY_CHAPTERS.forEach((c, i) => {
    if (!c.event) return;
    const done = i < index || (i === index && p >= 0.7);
    if (!done) return;
    const s = c.event.stats;
    total.msgs += s.msgs ?? 0;
    total.tools += s.tools ?? 0;
    total.docs += s.docs ?? 0;
    total.asks += s.asks ?? 0;
    total.blocked += s.blocked ?? 0;
  });
  return total;
}

/** Sky colour for the aurora, by clock hour: night indigo, dawn amber, day sky, dusk violet. */
export function skyFor(hour: number): string {
  const stops: [number, [number, number, number]][] = [
    [0, [67, 56, 202]], [5, [67, 56, 202]], [6.5, [245, 158, 11]], [9, [14, 165, 233]], [16, [14, 165, 233]],
    [18.5, [236, 72, 153]], [20.5, [109, 40, 217]], [24, [67, 56, 202]],
  ];
  let a = stops[0];
  let b = stops[stops.length - 1];
  for (let i = 1; i < stops.length; i++) {
    if (hour <= stops[i][0]) {
      a = stops[i - 1];
      b = stops[i];
      break;
    }
  }
  const k = b[0] === a[0] ? 0 : (hour - a[0]) / (b[0] - a[0]);
  const c = a[1].map((v, i) => Math.round(v + (b[1][i] - v) * k));
  return `rgb(${c[0]},${c[1]},${c[2]})`;
}
