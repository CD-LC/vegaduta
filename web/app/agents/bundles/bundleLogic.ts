import { BUNDLE_FORMAT, type AgentBundle } from "../../lib/api/agentBundles";

// Pure helpers for /agents/bundles: parse what the person pasted or uploaded, and describe the
// ready-made bundles shipped with the console. No React, no network.

/** Same cap the server applies to the agent design, with room for the wrapper. */
export const MAX_BUNDLE_BYTES = 80_000;

export type ParseFailure = "empty" | "too-large" | "invalid-json" | "not-a-bundle" | "wrong-format";

// One of the two is set. (Not a discriminated union: the console's tsconfig is not strict, where
// TypeScript does not narrow on a boolean tag.)
export interface ParsedBundle {
  bundle: AgentBundle | null;
  reason: ParseFailure | null;
}

const fail = (reason: ParseFailure): ParsedBundle => ({ bundle: null, reason });

export function parseBundle(text: string): ParsedBundle {
  const trimmed = text.trim();
  if (!trimmed) return fail("empty");
  if (new Blob([trimmed]).size > MAX_BUNDLE_BYTES) return fail("too-large");
  let value: unknown;
  try {
    value = JSON.parse(trimmed);
  } catch {
    return fail("invalid-json");
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return fail("not-a-bundle");
  const b = value as Record<string, unknown>;
  if (typeof b.title !== "string" || !b.agent || typeof b.agent !== "object" || Array.isArray(b.agent)) {
    return fail("not-a-bundle");
  }
  if (b.format !== BUNDLE_FORMAT) return fail("wrong-format");
  return { bundle: b as unknown as AgentBundle, reason: null };
}

export interface ReadyMadeBundle {
  readonly id: string;
  /** File under /agent-bundles/ (a copy of examples/agent-bundles, held equal by a test). */
  readonly file: string;
  /** Needs a connected account (Google) before it can do its job. */
  readonly needs: "nothing" | "google";
}

export const READY_MADE: readonly ReadyMadeBundle[] = [
  { id: "personal-assistant", file: "personal-assistant.json", needs: "nothing" },
  { id: "daily-briefing", file: "daily-briefing.json", needs: "nothing" },
  { id: "research-analyst", file: "research-analyst.json", needs: "nothing" },
  { id: "meeting-notes-to-actions", file: "meeting-notes-to-actions.json", needs: "nothing" },
];

export function readyMadeUrl(b: ReadyMadeBundle): string {
  return `/agent-bundles/${b.file}`;
}

/** Download an agent bundle as a file the person can send on. */
export function bundleFileName(title: string): string {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return `${slug || "agent"}.agent.json`;
}
