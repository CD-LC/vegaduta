// Ask-the-court: the command palette's "where do I ...?" answer, as a static
// table (Earned Autonomy, 2026-09-26). No model, no network - a handful of
// intents, each mapped to the route and the data-tour id of the control that
// answers it, opened as a `?tour=field:<id>` deep link (see ./deepLink.ts).
//
// Scoring: an intent needs its `required` pattern; every `bonus` pattern that
// also matches adds one. Highest score wins, ties go to table order, so
// "whatsapp api key" lands on the WhatsApp card (listed first) rather than the
// vault. An id that does not exist on the page it opens (say, no agent is open
// so the Workbench is not mounted) degrades to just opening the page.
//
// Pure: unit-tested in askCourt.test.ts.

import { fieldHref } from "./deepLink";

export type AskIntentId = "whatsapp" | "secret" | "tool" | "runs" | "model" | "prompt";

export type AskIntent = {
  id: AskIntentId;
  route: string;
  /** data-tour id to spotlight on arrival; omitted where the page itself is the answer. */
  tourId?: string;
  required: RegExp;
  bonus: RegExp[];
};

export const ASK_INTENTS: readonly AskIntent[] = [
  { id: "whatsapp", route: "/admin/channels", tourId: "channel-whatsapp", required: /whats\s?app/, bonus: [/token|key|secret|credential|number|connect|set\s?up|setup/] },
  { id: "secret", route: "/secrets", tourId: "secret-add", required: /secret|api[\s-]?key|password|vault|credential/, bonus: [/\b(add|new|create|store|save)\b/] },
  { id: "tool", route: "/tools", tourId: "mcp-connect", required: /\b(tools?|mcp|integrations?|connectors?)\b/, bonus: [/\b(connect|add|enable|install|plug)\b/] },
  { id: "runs", route: "/jobs", required: /\b(runs?|history|executions?|jobs?)\b/, bonus: [/\b(see|show|view|check|past|recent)\b/, /\bmy\b/] },
  { id: "model", route: "/agents", tourId: "workbench-model", required: /\b(models?|llm|provider)\b/, bonus: [/\b(change|switch|pick|choose|set|use)\b/] },
  { id: "prompt", route: "/agents", tourId: "workbench-system-prompt", required: /system\s?prompt|instructions?|persona/, bonus: [/\b(edit|change|write|set|update)\b/] },
];

export type AskMatch = { id: AskIntentId; route: string; tourId?: string; href: string };

export function classifyIntent(query: string | null | undefined): AskMatch | null {
  if (typeof query !== "string") return null;
  const q = query.trim().toLowerCase();
  // Two words minimum: a bare "model" or "tools" is a nav-row search, which the
  // palette already answers; the court is for questions.
  if (q.split(/\s+/).filter(Boolean).length < 2) return null;
  let best: { intent: AskIntent; score: number } | null = null;
  for (const intent of ASK_INTENTS) {
    if (!intent.required.test(q)) continue;
    const score = 1 + intent.bonus.filter((b) => b.test(q)).length;
    if (!best || score > best.score) best = { intent, score };
  }
  if (!best) return null;
  const { id, route, tourId } = best.intent;
  return { id, route, tourId, href: tourId ? fieldHref(route, tourId) : route };
}
