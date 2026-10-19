// Pure figures for the Memory overview. No React, no i18n, no clock (memoryStats.test.ts).
//
// What these numbers are allowed to claim, checked against core:
//   - GET /api/memory (UserMemoryController.overview) returns EVERY fact of the signed-in user: both queries behind
//     it (AgentMemoryService.listAllForSubject, TenantMemoryService.listFactsForSubject) select by subject with no
//     LIMIT and the controller does not paginate. So counts over `facts` are totals, not a sample. If that endpoint
//     ever gains a cap, every label fed from here has to say "of the N shown".
//   - "Never used" is `useCount == 0`, NOT `lastUsedAt == null`: last_used_at is NOT NULL DEFAULT now() in both
//     memory tables (V18, V77), so a fact that was never recalled still carries the time it was written. use_count
//     only moves when a fact is recalled into a conversation (touchUsage).
//   - useCount is optional in the type because an older core omits it; then usage is unknown and nothing about
//     usage is shown at all (rather than calling every fact "never used").
import type { MemoryFact } from "./memoryTypes";

export interface MemoryStats {
  total: number;
  /** scope "agent": private to the one assistant that learned it. */
  agent: number;
  /** scope "shared": visible to every assistant that has shared memory on. */
  shared: number;
  /** false when no fact carries a numeric useCount (older core): hide every usage figure. */
  usageKnown: boolean;
  /** Facts with useCount 0. Meaningful only when `usageKnown`. */
  neverUsed: number;
  /** Facts with useCount > 0, most used first (ties: newest first). */
  used: MemoryFact[];
  /** ISO createdAt of the newest / oldest fact, null when no fact has a readable date. */
  newest: string | null;
  oldest: string | null;
}

function time(iso: string | null | undefined): number | null {
  if (!iso) return null;
  const ms = new Date(iso).getTime();
  return Number.isNaN(ms) ? null : ms;
}

export function memoryStats(facts: readonly MemoryFact[] | null | undefined): MemoryStats {
  const list = facts ?? [];
  const stats: MemoryStats = {
    total: list.length,
    agent: 0,
    shared: 0,
    usageKnown: false,
    neverUsed: 0,
    used: [],
    newest: null,
    oldest: null,
  };
  let newestMs: number | null = null;
  let oldestMs: number | null = null;
  for (const fact of list) {
    if (fact.scope === "shared") stats.shared += 1;
    else stats.agent += 1;

    if (typeof fact.useCount === "number" && Number.isFinite(fact.useCount)) {
      stats.usageKnown = true;
      if (fact.useCount > 0) stats.used.push(fact);
      else stats.neverUsed += 1;
    }

    const ms = time(fact.createdAt);
    if (ms !== null) {
      if (newestMs === null || ms > newestMs) {
        newestMs = ms;
        stats.newest = fact.createdAt ?? null;
      }
      if (oldestMs === null || ms < oldestMs) {
        oldestMs = ms;
        stats.oldest = fact.createdAt ?? null;
      }
    }
  }
  stats.used.sort((a, b) => (b.useCount ?? 0) - (a.useCount ?? 0) || (time(b.createdAt) ?? 0) - (time(a.createdAt) ?? 0));
  return stats;
}

/** One line of a fact for a chart label: whitespace collapsed, cut at `max` characters with "...". */
export function factLabel(content: string | null | undefined, max = 70): string {
  const flat = (content || "").replace(/\s+/g, " ").trim();
  if (flat.length <= max) return flat;
  return `${flat.slice(0, Math.max(1, max - 3)).trimEnd()}...`;
}
