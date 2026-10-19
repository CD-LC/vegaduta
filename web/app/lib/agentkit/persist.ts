// agentkit — saving a run so a reload can continue it.
//
// localStorage, on this device only, keyed by the folder's id. Nothing here is
// sent anywhere — the saved state holds excerpts of the person's source, and
// the /code promise is that those never leave the browser.
//
// Best-effort by design: storage can be full, blocked (Safari private mode,
// enterprise policy) or simply absent (SSR). Every failure means "this run is
// not resumable", never an error in front of the person and never a lost
// write — the run itself carries on in memory.
//
// Two things are deliberately NOT saved: excerpts of files that look like
// secrets (a person who said "yes, read .env" said it for that run's window,
// not for a copy in localStorage), and the list of those approvals (a resumed
// run asks again).

import { isSecretPath } from "./paths";
import type { AgentRunState, PlanItem } from "./types";

const KEY_PREFIX = "vegaduta.agentkit.run.";
/** localStorage is ~5 MB per origin and shared with the rest of the app. A run
 *  state past this is dropped rather than squeezing everything else out. */
export const MAX_SAVED_CHARS = 400_000;

function storage(): Storage | null {
  try {
    if (typeof window === "undefined" || !window.localStorage) return null;
    return window.localStorage;
  } catch {
    // Reading the property itself throws when storage is blocked.
    return null;
  }
}

/** What actually gets written: the state minus anything secret. Pure. */
export function sanitiseForSave(state: AgentRunState): AgentRunState {
  return {
    ...state,
    workingSet: state.workingSet.filter((slice) => !isSecretPath(slice.path)),
    secretReadsAllowed: [],
  };
}

export function saveRun(repoId: string, state: AgentRunState): boolean {
  const store = storage();
  if (!store || !repoId) return false;
  try {
    const json = JSON.stringify(sanitiseForSave(state));
    if (json.length > MAX_SAVED_CHARS) {
      store.removeItem(KEY_PREFIX + repoId);
      return false;
    }
    store.setItem(KEY_PREFIX + repoId, json);
    return true;
  } catch {
    return false; // QuotaExceededError, a cyclic value, a blocked store
  }
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((v) => typeof v === "string");
}

/**
 * Validate a parsed value as a run state. Saved JSON is untrusted input by the
 * time it is read back — an older build wrote it, or an extension edited it —
 * and a malformed state fed to the loop would fail somewhere far less obvious
 * than here. Anything that does not check out is "no saved run".
 */
export function reviveRun(value: unknown): AgentRunState | null {
  if (!value || typeof value !== "object") return null;
  const v = value as Record<string, unknown>;
  if (v.version !== 1 || typeof v.task !== "string" || !v.task.trim()) return null;
  if (!Array.isArray(v.plan) || !Array.isArray(v.turns) || !Array.isArray(v.workingSet)) return null;
  if (!isStringArray(v.summary) || !isStringArray(v.recentFingerprints)) return null;
  const num = (x: unknown): number => (typeof x === "number" && Number.isFinite(x) && x >= 0 ? Math.trunc(x) : 0);

  const plan: PlanItem[] = v.plan
    .filter((p): p is { text: string; status: string } => !!p && typeof p === "object" && typeof (p as { text?: unknown }).text === "string")
    .map((p): PlanItem => ({ text: p.text, status: p.status === "done" ? "done" : p.status === "doing" ? "doing" : "todo" }));

  const turns = v.turns
    .filter((t): t is Record<string, unknown> => !!t && typeof t === "object" && typeof (t as { step?: unknown }).step === "number")
    .map((t) => {
      const call = t.call as { name?: unknown; args?: unknown } | null;
      const validCall =
        call && typeof call === "object" && typeof call.name === "string" && call.args && typeof call.args === "object" && !Array.isArray(call.args);
      return {
        step: num(t.step),
        call: validCall ? { name: call.name as string, args: call.args as Record<string, unknown> } : null,
        note: typeof t.note === "string" ? t.note : "",
        observation: typeof t.observation === "string" ? t.observation : "",
        observationEvicted: t.observationEvicted === true,
        failed: t.failed === true,
      };
    });

  const workingSet = v.workingSet
    .filter(
      (s): s is Record<string, unknown> =>
        !!s && typeof s === "object" && typeof (s as { path?: unknown }).path === "string" && typeof (s as { text?: unknown }).text === "string"
    )
    .map((s) => {
      const slice: AgentRunState["workingSet"][number] = {
        path: s.path as string,
        startLine: num(s.startLine),
        endLine: num(s.endLine),
        totalLines: num(s.totalLines),
        text: s.text as string,
        touchedStep: num(s.touchedStep),
      };
      // Without the ranges a resumed multi-range excerpt would go back to
      // claiming one continuous span in its header.
      if (Array.isArray(s.ranges)) {
        const ranges = s.ranges
          .filter((r): r is Record<string, unknown> => !!r && typeof r === "object")
          .map((r) => ({ start: num(r.start), end: num(r.end) }))
          .filter((r) => r.start >= 1 && r.end >= r.start);
        if (ranges.length > 0) slice.ranges = ranges;
      }
      return slice;
    });

  const recentHits: Record<string, number[]> = {};
  if (v.recentHits && typeof v.recentHits === "object" && !Array.isArray(v.recentHits)) {
    for (const [path, lines] of Object.entries(v.recentHits as Record<string, unknown>)) {
      if (Array.isArray(lines)) recentHits[path] = lines.filter((n): n is number => typeof n === "number" && Number.isFinite(n));
    }
  }

  const statuses = ["idle", "running", "awaiting-approval", "finished", "stopped", "stalled", "step-budget", "token-budget", "window-too-small", "failed"];
  return {
    version: 1,
    task: v.task,
    plan,
    turns,
    summary: v.summary,
    summaryDropped: num(v.summaryDropped),
    workingSet,
    recentHits,
    stepsUsed: num(v.stepsUsed),
    tokensUsed: num(v.tokensUsed),
    mutationEpoch: num(v.mutationEpoch),
    recentFingerprints: v.recentFingerprints,
    repeatCount: num(v.repeatCount),
    malformedStreak: num(v.malformedStreak),
    status: (typeof v.status === "string" && statuses.includes(v.status) ? v.status : "stopped") as AgentRunState["status"],
    answer: typeof v.answer === "string" ? v.answer : "",
    secretReadsAllowed: [],
  };
}

export function loadRun(repoId: string): AgentRunState | null {
  const store = storage();
  if (!store || !repoId) return null;
  try {
    const raw = store.getItem(KEY_PREFIX + repoId);
    if (!raw) return null;
    return reviveRun(JSON.parse(raw));
  } catch {
    return null;
  }
}

export function clearRun(repoId: string): void {
  const store = storage();
  if (!store || !repoId) return;
  try {
    store.removeItem(KEY_PREFIX + repoId);
  } catch {
    // Nothing to do.
  }
}
