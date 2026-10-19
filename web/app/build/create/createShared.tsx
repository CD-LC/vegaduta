import type { ComponentType } from "react";
import { Bot, BookOpen, CalendarClock, FileText, MessageCircle, Search, Smartphone, Target, Workflow } from "lucide-react";
import type { RawBuildError } from "../../lib/build/explainError";

export type Stage = "describe" | "blueprint" | "building" | "done";

export const POLL_MS = 1500;
// Give up WATCHING after this long (the job itself keeps running server-side).
export const POLL_MAX_MS = 10 * 60 * 1000;
// Tolerate a few failed status reads before treating the build as failed.
export const POLL_MAX_FAILURES = 4;

/** True when the person asked the OS for less motion. Safe without matchMedia (SSR, jsdom). */
export function prefersReducedMotion(): boolean {
  try {
    return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

export const RECIPE_ICON: Record<string, ComponentType<{ className?: string }>> = {
  support: MessageCircle,
  leadflow: Target,
  documents: FileText,
  research: Search,
  knowledge: BookOpen,
  digest: CalendarClock,
};

// The flight path shown under the describe-stage hero: agent first (it is the
// intelligence everything else runs on), then the workflow that orchestrates
// it, then the app that puts both in people's hands.
export const FLIGHT_PATH = [
  { step: "01 · START HERE", title: "Custom agent", text: "Instructions, model, knowledge, tools and guardrails.", Icon: Bot, primary: true },
  { step: "02 · ORCHESTRATE", title: "Workflow", text: "Triggers, approvals and repeatable work around the agent.", Icon: Workflow, primary: false },
  { step: "03 · PUT IT IN HANDS", title: "AI-powered app", text: "Use your agent and workflows to power an experience people can use.", Icon: Smartphone, primary: false },
] as const;

/** Everything useful an error carries, flattened to text (server "errors" arrays included).
 *  A 422's `errors` entries are {section, message} objects - render them as
 *  "section: message", not String(object) which would print "[object Object]". */
function issueText(x: unknown): string {
  if (x && typeof x === "object" && "message" in x) {
    const { section, message } = x as { section?: unknown; message?: unknown };
    return `${typeof section === "string" && section ? `${section}: ` : ""}${String(message ?? x)}`;
  }
  return String(x);
}

export function toRaw(e: unknown): RawBuildError {
  const err = e as { message?: unknown; status?: unknown; errors?: unknown } | null;
  const extra = Array.isArray(err?.errors) ? ` ${(err!.errors as unknown[]).map(issueText).join("; ")}` : "";
  return { message: `${typeof err?.message === "string" ? err.message : ""}${extra}`.trim(), status: err?.status };
}

/** Which engine drafted the blueprint (disclosed to the person, never guessed). */
export interface DraftEngine {
  kind: "device" | "recipes" | "platform" | "byok";
  model: string | null;
}
