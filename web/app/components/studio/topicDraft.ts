// The briefing: turn the visitor's OWN answers into a real agent draft.
//
// Nothing here is a pre-built agent. There is no gallery, no role list and no
// default topic - the studio asks, and the mission, name, tools, thinking depth,
// autonomy and rules are all composed from what the visitor said. No model runs
// here and nothing typed is sent anywhere until they press Build; it is plain
// templating on their device, so it is instant, free, and works for a job we
// never anticipated ("beekeeping in Coorg", "my thesis on monsoon forecasting").
//
// The draft is only a starting point: it is created as an ordinary agent the
// visitor can rewrite, arm with documents and connectors, and delete.
//
// Pure: no React, no DOM, no fetch. Unit-tested in studio.test.ts.

import type { AgentSpec } from "./studioClient";

export type Care = "FAST" | "CAREFUL" | "RIGOROUS";

export type Brief = {
  /** The job, in the visitor's words. Required. */
  job: string;
  /** Who it works for / who reads its output. Optional. */
  audience: string;
  /** What it must never do. Optional, but the single most valuable answer. */
  never: string;
  /** How much checking it does before answering. */
  care: Care;
  /** How freely it may act without asking. */
  autonomy: "SEMI_AUTONOMOUS" | "FULLY_AUTONOMOUS";
  /** 0 = same answer every time, 1 = varied. */
  creativity: number;
  /** Which kinds of hands the job needs. */
  needs: { web: boolean; code: boolean; api: boolean; docs: boolean };
};

export const EMPTY_BRIEF: Brief = {
  job: "",
  audience: "",
  never: "",
  care: "CAREFUL",
  autonomy: "SEMI_AUTONOMOUS",
  creativity: 0.4,
  needs: { web: false, code: false, api: false, docs: false },
};

export type Draft = {
  spec: AgentSpec & { name: string; mission: string; tools: string[]; guardrails: string[] };
  /** Suggested first mission, prefilled on the Run stage. */
  firstMission: string;
  sampleMissions: string[];
  mcpQuery: string;
  docIdea: string;
};

const has = (text: string, words: string[]) => words.some((w) => new RegExp(`\\b${w}`, "i").test(text));

const STOP = new Set([
  "a",
  "an",
  "the",
  "for",
  "of",
  "to",
  "and",
  "my",
  "me",
  "our",
  "your",
  "in",
  "on",
  "with",
  "that",
  "this",
  "about",
  "help",
  "helps",
  "helping",
  "agent",
  "assistant",
  "i",
  "want",
  "need",
  "build",
  "make",
  "create",
  "please",
  "from",
  "is",
  "are",
  "it",
  "them",
  "their",
  "at",
  "by",
]);

/** "customer support for a small online shop" -> "Customer support agent". Never longer than the server's 60 characters. */
export function nameFromTopic(topic: string): string {
  const words = topic
    .replace(/\(.*?\)/g, " ")
    .split(/[^\p{L}\p{N}']+/u)
    .filter((w) => w && !STOP.has(w.toLowerCase()));
  const head = words.slice(0, 3).join(" ");
  const base = head ? head.charAt(0).toUpperCase() + head.slice(1) : "New";
  return `${base} agent`.slice(0, 60);
}

/** Which hands the words of a job suggest - used only to PRE-TICK the "what does it need" boxes, which the visitor then owns. */
export function suggestNeeds(job: string): Brief["needs"] {
  return {
    web: has(job, [
      "research",
      "news",
      "market",
      "compet",
      "price",
      "pricing",
      "compare",
      "latest",
      "trend",
      "travel",
      "trip",
      "weather",
      "review",
      "find",
      "monitor",
      "watch",
      "paper",
      "literature",
      "lead",
      "look up",
    ]),
    code: has(job, [
      "tax",
      "gst",
      "budget",
      "loan",
      "emi",
      "roi",
      "profit",
      "margin",
      "invest",
      "calculat",
      "forecast",
      "expense",
      "salary",
      "cost",
      "score",
      "analy",
      "csv",
      "spreadsheet",
    ]),
    api: has(job, ["api", "weather", "rates", "currency", "github", "stock", "json", "crypto"]),
    docs: has(job, [
      "policy",
      "faq",
      "manual",
      "handbook",
      "contract",
      "agreement",
      "resume",
      "notes",
      "textbook",
      "chapter",
      "report",
      "warranty",
      "document",
      "pdf",
      "thesis",
      "support",
      "screening",
      "summar",
      "price list",
    ]),
  };
}

/** Suggests how careful to be from the stakes in the words. The visitor can override it. */
export function suggestCare(job: string): Care {
  if (
    has(job, [
      "legal",
      "contract",
      "agreement",
      "medical",
      "clinic",
      "patient",
      "tax",
      "invest",
      "loan",
      "insurance",
      "compliance",
    ])
  )
    return "RIGOROUS";
  if (has(job, ["practice", "fun", "game", "story", "joke", "chat", "brainstorm", "coach"])) return "FAST";
  return "CAREFUL";
}

function clean(s: string, max: number): string {
  return s.replace(/\s+/g, " ").trim().slice(0, max);
}

export function briefReady(b: Brief): boolean {
  return clean(b.job, 240).length >= 6;
}

/** Compose a real agent from the visitor's answers. */
export function draftFromBrief(brief: Brief): Draft {
  const job = clean(brief.job, 240);
  const audience = clean(brief.audience, 160);
  const never = clean(brief.never, 300);
  const highStakes = brief.care === "RIGOROUS";

  const tools = new Set<string>(["remember_fact", "recall_related_facts"]);
  if (brief.needs.web) {
    tools.add("web_search");
    tools.add("fetch_web_page");
  }
  if (brief.needs.code) tools.add("run_code");
  if (brief.needs.api) tools.add("http_request");
  if (brief.needs.docs) tools.add("search_knowledge_base");

  const mission = [
    `You are an expert AI agent. Your job: ${job}.`,
    audience ? `You work for, and write for: ${audience}.` : null,
    "",
    "How you work:",
    "- Start by working out what is actually being asked. If a missing detail would change the answer, ask ONE short question; otherwise make a sensible assumption and say so.",
    brief.needs.docs
      ? "- The user may attach documents. Search them first with the knowledge tool and answer from them: quote the relevant line and name the document. Never present something as coming from a document when it did not."
      : null,
    brief.needs.web
      ? "- For anything that changes over time (prices, rules, news, availability), look it up on the web and give the source. Say when sources disagree."
      : null,
    brief.needs.code
      ? "- Do every calculation with the code tool rather than in your head. Show the inputs, the formula and the result."
      : null,
    brief.needs.api
      ? "- When a public API can answer the question, call it and read the real response instead of guessing."
      : null,
    "- Be concrete and short: lead with the answer, then the reasoning, then next steps.",
    highStakes
      ? "- This area is high-stakes. You are not a licensed professional: state your confidence, flag what a human should check, and never present guesses as facts."
      : "- Say plainly when you are unsure. Never invent facts, sources or numbers.",
    "- Before any action that cannot be undone (sending, paying, deleting), stop and ask the user to confirm.",
    never ? `\nYou must NEVER: ${never}` : null,
  ]
    .filter((l): l is string => l !== null)
    .join("\n");

  const sampleMissions = [
    `What are the three most useful things you can do for me on this - ${job}? Be specific.`,
    brief.needs.docs
      ? "Summarise the document I attached in five bullet points, then list what I should do next."
      : `Give me a one-week plan for: ${job}.`,
    brief.needs.web
      ? "Look up what changed in this area in the last month and tell me what matters."
      : "Ask me the five questions you need answered to do this job well.",
  ];

  return {
    spec: {
      name: nameFromTopic(job),
      mission: mission.slice(0, 4000),
      reasoningDepth: brief.care,
      autonomy: brief.autonomy,
      tools: [...tools],
      guardrails: ["profanity", "jailbreak", ...(highStakes ? ["pii"] : [])],
      temperature: Math.max(0, Math.min(1.5, brief.creativity * 1.2)),
      maxTokens: 1024,
    },
    firstMission: sampleMissions[0],
    sampleMissions,
    mcpQuery: firstKeyword(job),
    docIdea: brief.needs.docs ? "the documents this job depends on" : "anything you would hand a new colleague",
  };
}

/** Back-compat for callers that only have one sentence: a brief with the suggestions ticked for them. */
export function draftFromTopic(rawTopic: string): Draft {
  const job = clean(rawTopic, 240);
  return draftFromBrief({ ...EMPTY_BRIEF, job, care: suggestCare(job), needs: suggestNeeds(job) });
}

function firstKeyword(topic: string): string {
  const words = topic
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length > 3 && !STOP.has(w));
  return words[0] ?? "search";
}

/** Example placeholders for the job box. They are never selectable agents - only hints of the SHAPE of a good answer. */
export const JOB_PLACEHOLDERS = [
  "Answer my customers' questions from my price list…",
  "Watch three competitors and tell me what changed…",
  "Explain my rental agreement in plain English…",
  "Plan a 4-day trip and check the weather…",
  "Review my pull requests before I do…",
  "Tutor my daughter in Class 10 science…",
];

/**
 * Starting ideas across very different fields, shown as chips under the job box. A chip only fills the text box
 * (the visitor can rewrite every word); it never creates an agent, so the agent is still composed from what they
 * chose to say. Kept deliberately unlike each other so nobody mistakes the studio for a customer-support tool.
 */
export const JOB_IDEAS: { label: string; job: string }[] = [
  {
    label: "Chase unpaid invoices",
    job: "Every Monday, list my unpaid invoices older than 15 days and draft a polite reminder for each customer.",
  },
  {
    label: "Track competitor prices",
    job: "Watch three competitor websites and tell me each morning which prices or offers changed.",
  },
  {
    label: "Read my contract",
    job: "Explain my rental agreement in plain English and flag any clause that could cost me money.",
  },
  {
    label: "Plan a trip",
    job: "Plan a 4-day trip within my budget, check the weather each day and suggest what to pack.",
  },
  {
    label: "Class 10 science tutor",
    job: "Tutor my daughter in Class 10 science: ask one question at a time and explain mistakes simply.",
  },
  {
    label: "Screen job applicants",
    job: "Read incoming CVs against my job description and rank the top five with one line on why.",
  },
  {
    label: "Review pull requests",
    job: "Review my pull requests before I do and point out bugs, missing tests and unclear names.",
  },
  {
    label: "Weekly farm report",
    job: "Summarise this week's mandi prices for tomatoes and onions and tell me the best day to sell.",
  },
  {
    label: "Explain a mainframe abend",
    job: "When a batch job abends, explain the code (S0C7, SB37, U4038) in plain English, then give me the fix and the safe rerun steps.",
  },
];

/** A short sample document, clearly labelled, so a visitor with nothing to hand can still see document search work. */
export function sampleDocument(topic: string): { title: string; text: string } {
  const t = topic.trim() || "your business";
  const text = [
    `Sample notes - ${t}`,
    "(This is a sample document written by the studio so you can try document search. Replace it with your own.)",
    "",
    "Opening hours: Monday to Saturday, 9:30 to 18:00. Closed on Sundays and public holidays.",
    "Response time: we reply to every message within one working day; urgent issues within 2 hours.",
    "Refunds: requests are accepted within 7 days of delivery. Items must be unused and in original packaging. Refunds go back to the original payment method within 5 to 7 working days.",
    "Delivery: orders above 999 rupees ship free. Standard delivery takes 3 to 5 days; express takes 1 to 2 days and costs 149 rupees.",
    "Escalation: anything involving a payment dispute, a medical concern or a legal threat must be passed to the owner.",
    "Tone: friendly, brief, and honest. Never promise a date we cannot confirm.",
  ].join("\n");
  return { title: "sample-notes.txt", text };
}
