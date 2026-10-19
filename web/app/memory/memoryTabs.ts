// Pure helpers for the Memory page's section tabs: no React, no i18n, no DOM,
// so the rules (which tabs exist, what a #hash means, what the badges count)
// are unit-testable on their own (memoryTabs.test.ts).
import { ACTIVE_PROJECT_LABEL } from "./memoryFilters";
import type { MemoryBlock, MemoryAgent } from "./memoryTypes";

/**
 * The page is five jobs, in the order a person meets them:
 *  notes      - tell assistants about you and your projects (what they are told)
 *  assistants - choose which assistants may read those notes (who is told)
 *  facts      - what assistants have learned on their own (what they saved)
 *  import     - bring memory and chat history in from elsewhere
 *  privacy    - download or erase everything
 */
export const MEMORY_TABS = ["notes", "assistants", "facts", "import", "privacy"] as const;
export type MemoryTab = (typeof MEMORY_TABS)[number];

export function isMemoryTab(value: unknown): value is MemoryTab {
  return typeof value === "string" && (MEMORY_TABS as readonly string[]).includes(value);
}

/** "#facts" -> "facts"; anything that is not a tab id -> null. */
export function tabFromHash(hash: string | null | undefined): MemoryTab | null {
  const id = (hash || "").replace(/^#/, "").trim().toLowerCase();
  return isMemoryTab(id) ? id : null;
}

/**
 * The tab to show: the one asked for if it is available, else the first
 * available one (Notes is hidden when the backend has blocks off, Privacy when
 * self-service is off).
 */
export function resolveTab(requested: MemoryTab | null, available: readonly MemoryTab[]): MemoryTab | null {
  if (requested && available.includes(requested)) return requested;
  return available[0] ?? null;
}

/** Written notes (persona, about me, current work, project notes); the active-project pointer is not a note. */
export function writtenNoteCount(blocks: readonly MemoryBlock[] | null | undefined): number {
  return (blocks ?? []).filter((b) => b.label !== ACTIVE_PROJECT_LABEL && (b.content || "").trim() !== "").length;
}

export function optedInAgentCount(agents: readonly MemoryAgent[] | null | undefined): number {
  return (agents ?? []).filter((a) => a.shareMemoryWithTenant).length;
}

/**
 * True when the person has written notes but no assistant would ever read them:
 * the one dependency a person cannot see from the Notes tab alone.
 */
export function notesAreUnused(
  blocks: readonly MemoryBlock[] | null | undefined,
  agents: readonly MemoryAgent[] | null | undefined
): boolean {
  if (agents == null || agents.length === 0) return false;
  return writtenNoteCount(blocks) > 0 && optedInAgentCount(agents) === 0;
}
