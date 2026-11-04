// Pure helpers for the unified-memory items panel (ADR-0028 WP6): no React, no
// i18n, no DOM, so the grouping/flag rules are unit-testable on their own
// (memoryItems.test.ts), matching memoryTabs.ts / memoryFilters.ts.

import type { MemoryItem } from "./memoryItemTypes";

/** The settings-catalog key every level of the unified-memory gate reads (MemoryUnifiedSettings.ENABLED_KEY). */
export const UNIFIED_ENABLED_KEY = "memory.unified.enabled";

/**
 * The three groups the panel shows, in display order:
 *  review     - PENDING proposals and QUARANTINED writes a person must approve or reject
 *  active     - TRUSTED + ACTIVE items recall can return
 *  tombstoned - REJECTED, SUPERSEDED and REVERTED rows kept for the audit trail
 * Erased rows never come back from the API (only an ERASED event tombstone stays).
 */
export type ItemBucket = "review" | "active" | "tombstoned";
export const ITEM_BUCKETS: readonly ItemBucket[] = ["review", "active", "tombstoned"];

/** Anything not ACTIVE and not a known end state counts as needing review (fail closed, like the server). */
export function itemBucket(item: Pick<MemoryItem, "status" | "trust">): ItemBucket {
  if (item.status === "ACTIVE" && item.trust !== "QUARANTINED") return "active";
  if (item.status === "REJECTED" || item.status === "SUPERSEDED" || item.status === "REVERTED") {
    return "tombstoned";
  }
  return "review";
}

export type GroupedItems = Record<ItemBucket, MemoryItem[]>;

/** Split a flat list into the three display groups; input order (newest first) is kept inside each. */
export function groupMemoryItems(items: readonly MemoryItem[]): GroupedItems {
  const out: GroupedItems = { review: [], active: [], tombstoned: [] };
  for (const item of items) out[itemBucket(item)].push(item);
  return out;
}

/** Approve/reject is only meaningful for a PENDING proposal (the server 409s anything else). */
export function isPending(item: Pick<MemoryItem, "status">): boolean {
  return item.status === "PENDING";
}

/** Revert applies to an ACTIVE row; tombstoned and pending rows cannot be reverted. */
export function isActive(item: Pick<MemoryItem, "status">): boolean {
  return item.status === "ACTIVE";
}

/** Kinds the API documents today; a forward-compatible labelKey() covers anything newer. */
export const ITEM_KINDS = ["EPISODIC", "SEMANTIC", "PROCEDURAL", "SELF_MODEL"] as const;

/** `labelKey("kind", "SELF_MODEL")` -> "kind_SELF_MODEL"; a missing value maps to the `kind_other` fallback key. */
export function labelKey(prefix: string, value: string | null | undefined): string {
  return `${prefix}_${value ? value.toUpperCase() : "other"}`;
}

/**
 * Minimal shape of the settings /effective answer this surface reads (the full
 * type lives in lib/api/settings.ts; declared here so the helper stays pure).
 */
export interface EffectiveValues {
  values: Record<string, { value: unknown } | undefined>;
}

/**
 * Whether the unified-memory gate can be on for any agent of this workspace:
 * the catalog resolves platform AND tenant into the tenant-level effective
 * value. A missing key or a non-true value means off (fail closed, like the
 * gate itself). Per-agent vetoes still surface as a 404 on the list call.
 */
export function unifiedMemoryEnabled(effective: EffectiveValues | null | undefined): boolean {
  return effective?.values?.[UNIFIED_ENABLED_KEY]?.value === true;
}
