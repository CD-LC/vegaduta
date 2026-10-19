// Live "prompt strength" hint for the Create surface goal box. Pure and
// instant: no model call, just three cheap checks - is there enough to go on,
// who is it for, and what should it actually do. Each gap offers a chip that
// appends a sentence the person can then edit.

export type PromptStrength = "empty" | "weak" | "okay" | "strong";

export interface PromptHint {
  id: "short" | "audience" | "outcome";
  message: string;
  /** Text a chip appends to the goal (the person edits the placeholders). */
  chipLabel: string;
  addition: string;
}

export interface PromptAnalysis {
  strength: PromptStrength;
  words: number;
  hints: PromptHint[];
}

const AUDIENCE_RXS: RegExp[] = [
  /\b(?:customers?|clients?|users?|students?|parents?|patients?|members?|visitors?|donors?|residents?|farmers?|devotees|staff|team|employees?|shoppers?|buyers?|sellers?|guests?|leads?|candidates?|applicants?|learners?|teachers?|owners?)\b/i,
  /\b(?:my|our) (?:\w+ ){0,2}(?:shop|store|clinic|school|business|salon|restaurant|class|institute|ngo|society|gym|agency|club|trust|factory|pharmacy|bakery|homestay|temple)\b/i,
  /\bfor (?:me|my|our|the)\b/i,
];

const OUTCOME_RX =
  /\b(answer|reply|respond|book|schedule|remind|reminder|send|draft|write|summari[sz]e|track|collect|screen|shortlist|recommend|explain|check|monitor|alert|notify|escalate|pass|hand ?off|forward|create|generate|build|turn|convert|help|greet|capture|log|compare|review|triage|flag|take orders?|bill)\b/i;

export function analysePrompt(text: string): PromptAnalysis {
  const t = (text || "").trim();
  if (!t) return { strength: "empty", words: 0, hints: [] };
  const words = t.split(/\s+/).filter(Boolean).length;
  const hints: PromptHint[] = [];

  if (words < 8) {
    hints.push({
      id: "short",
      message: "A bit short - a sentence or two gives a much better first draft.",
      chipLabel: "Add detail",
      addition: " Keep the tone friendly and keep answers short.",
    });
  }
  if (!AUDIENCE_RXS.some((rx) => rx.test(t))) {
    hints.push({
      id: "audience",
      message: "Who is it for? Naming the audience sharpens the tone and the guardrails.",
      chipLabel: "Add audience",
      addition: " It is for my customers, who message me on WhatsApp.",
    });
  }
  if (!OUTCOME_RX.test(t)) {
    hints.push({
      id: "outcome",
      message: "What should it actually do? Say the job in one verb, like answer, book or summarise.",
      chipLabel: "Add outcome",
      addition: " It should answer common questions and pass anything unusual to me.",
    });
  }

  const strength: PromptStrength = hints.length === 0 ? (words >= 12 ? "strong" : "okay") : hints.length >= 2 ? "weak" : "okay";
  return { strength, words, hints };
}

/** Append a hint's sentence to the goal with sane spacing. */
export function applyHint(goal: string, hint: Pick<PromptHint, "addition">): string {
  const base = goal.replace(/\s+$/, "");
  if (!base) return hint.addition.trim();
  const sep = /[.!?]$/.test(base) ? "" : ".";
  return `${base}${sep}${hint.addition}`;
}
