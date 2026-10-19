// Pure helpers for the Memory page: no React, no i18n, no clock, so they are
// unit-testable on their own (memoryFilters.test.ts).
import type { MemoryBlock, MemoryFact } from "./memoryTypes";

/** Reserved block that points at the project whose note rides along on every turn. */
export const ACTIVE_PROJECT_LABEL = "active_project";
export const PROJECT_PREFIX = "project:";

/** Scope filter values. "all" shows everything. */
export type FactScopeFilter = "all" | "agent" | "shared";

export type FactFilter = {
  /** Free-text search over the fact text; case-insensitive, whitespace-trimmed. */
  query: string;
  scope: FactScopeFilter;
  /** "" = any agent. A non-empty id also hides shared facts (they belong to no agent). */
  agentId: string;
};

export const EMPTY_FACT_FILTER: FactFilter = { query: "", scope: "all", agentId: "" };

export function isFactFilterActive(filter: FactFilter): boolean {
  return filter.query.trim() !== "" || filter.scope !== "all" || filter.agentId !== "";
}

export function filterFacts<T extends MemoryFact>(facts: readonly T[] | null | undefined, filter: FactFilter): T[] {
  const list = facts ?? [];
  const needle = filter.query.trim().toLowerCase();
  return list.filter((fact) => {
    if (filter.scope !== "all" && fact.scope !== filter.scope) return false;
    if (filter.agentId && fact.agentId !== filter.agentId) return false;
    if (needle && !(fact.content || "").toLowerCase().includes(needle)) return false;
    return true;
  });
}

/** Ids of the agents that own at least one private fact, in first-seen order. */
export function factAgentIds(facts: readonly MemoryFact[] | null | undefined): string[] {
  const seen = new Set<string>();
  for (const fact of facts ?? []) {
    if (fact.scope === "agent" && fact.agentId) seen.add(fact.agentId);
  }
  return [...seen];
}

/** The named project notes ("project:<slug>"), in the order the server sent them. */
export function projectBlocks<T extends MemoryBlock>(blocks: readonly T[] | null | undefined): T[] {
  return (blocks ?? []).filter((b) => b.label.startsWith(PROJECT_PREFIX));
}

export function projectSlug(label: string): string {
  return label.startsWith(PROJECT_PREFIX) ? label.slice(PROJECT_PREFIX.length) : label;
}

/**
 * The active project's slug, or null. The server stores the bare slug; a
 * "project:" prefix is tolerated, and a pointer at a project that no longer
 * has a note counts as no active project.
 */
export function activeProjectSlug(blocks: readonly MemoryBlock[] | null | undefined): string | null {
  const list = blocks ?? [];
  const pointer = list.find((b) => b.label === ACTIVE_PROJECT_LABEL);
  const slug = projectSlug((pointer?.content || "").trim().toLowerCase());
  if (!slug) return null;
  return list.some((b) => b.label === PROJECT_PREFIX + slug) ? slug : null;
}

/** True when at least one note (not the active-project pointer) has text in it. */
export function hasWrittenNotes(blocks: readonly MemoryBlock[] | null | undefined): boolean {
  return (blocks ?? []).some((b) => b.label !== ACTIVE_PROJECT_LABEL && (b.content || "").trim() !== "");
}

/** The typed confirmation for "forget everything": case and surrounding spaces do not matter. */
export function confirmationMatches(typed: string, expected: string): boolean {
  const want = expected.trim().toLowerCase();
  return want !== "" && typed.trim().toLowerCase() === want;
}

export type FactSort = "newest" | "oldest" | "mostUsed" | "neverUsed";
export const DEFAULT_FACT_SORT: FactSort = "newest";

function time(iso: string | null | undefined): number {
  const n = iso ? Date.parse(iso) : NaN;
  return Number.isNaN(n) ? 0 : n;
}

/** A sorted copy. Ties keep the server's order (Array.sort is stable); facts without a date sort as oldest. */
export function sortFacts<T extends MemoryFact>(facts: readonly T[], sort: FactSort): T[] {
  const uses = (f: T) => (typeof f.useCount === "number" ? f.useCount : 0);
  const list = [...facts];
  switch (sort) {
    case "newest":
      return list.sort((a, b) => time(b.createdAt) - time(a.createdAt));
    case "oldest":
      return list.sort((a, b) => time(a.createdAt) - time(b.createdAt));
    case "mostUsed":
      return list.sort((a, b) => uses(b) - uses(a));
    case "neverUsed":
      return list.sort((a, b) => uses(a) - uses(b));
  }
}
