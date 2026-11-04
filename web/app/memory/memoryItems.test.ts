import { describe, expect, it } from "vitest";
import {
  UNIFIED_ENABLED_KEY,
  groupMemoryItems,
  isActive,
  isPending,
  itemBucket,
  labelKey,
  unifiedMemoryEnabled,
} from "./memoryItems";
import type { MemoryItem } from "./memoryItemTypes";

const item = (over: Partial<MemoryItem>): MemoryItem => ({ id: over.id || "i", agentId: "a", content: "c", ...over });

describe("itemBucket", () => {
  it("puts PENDING rows and QUARANTINED writes in review", () => {
    expect(itemBucket(item({ status: "PENDING", trust: "TRUSTED" }))).toBe("review");
    expect(itemBucket(item({ status: "ACTIVE", trust: "QUARANTINED" }))).toBe("review");
    expect(itemBucket(item({}))).toBe("review");
  });

  it("puts TRUSTED ACTIVE rows in active", () => {
    expect(itemBucket(item({ status: "ACTIVE", trust: "TRUSTED" }))).toBe("active");
  });

  it("puts REJECTED, SUPERSEDED and REVERTED rows in tombstoned", () => {
    for (const status of ["REJECTED", "SUPERSEDED", "REVERTED"]) {
      expect(itemBucket(item({ status, trust: "TRUSTED" }))).toBe("tombstoned");
    }
  });

  it("fails closed: an unknown status is review, not active", () => {
    expect(itemBucket(item({ status: "SOMETHING_NEW", trust: "TRUSTED" }))).toBe("review");
  });
});

describe("groupMemoryItems", () => {
  it("splits into the three buckets and keeps order", () => {
    const items = [
      item({ id: "1", status: "ACTIVE", trust: "TRUSTED" }),
      item({ id: "2", status: "PENDING" }),
      item({ id: "3", status: "REJECTED" }),
      item({ id: "4", status: "ACTIVE", trust: "TRUSTED" }),
    ];
    const g = groupMemoryItems(items);
    expect(g.review.map((i) => i.id)).toEqual(["2"]);
    expect(g.active.map((i) => i.id)).toEqual(["1", "4"]);
    expect(g.tombstoned.map((i) => i.id)).toEqual(["3"]);
  });
});

describe("decision helpers", () => {
  it("isPending only for PENDING", () => {
    expect(isPending({ status: "PENDING" })).toBe(true);
    expect(isPending({ status: "ACTIVE" })).toBe(false);
  });
  it("isActive only for ACTIVE", () => {
    expect(isActive({ status: "ACTIVE" })).toBe(true);
    expect(isActive({ status: "REVERTED" })).toBe(false);
  });
});

describe("labelKey", () => {
  it("builds the i18n key and handles missing values", () => {
    expect(labelKey("kind", "SELF_MODEL")).toBe("kind_SELF_MODEL");
    expect(labelKey("status", null)).toBe("status_other");
    expect(labelKey("event", "erased")).toBe("event_ERASED");
  });
});

describe("unifiedMemoryEnabled", () => {
  it("is true only when the effective value is exactly true", () => {
    expect(unifiedMemoryEnabled({ values: { [UNIFIED_ENABLED_KEY]: { value: true } } })).toBe(true);
    expect(unifiedMemoryEnabled({ values: { [UNIFIED_ENABLED_KEY]: { value: false } } })).toBe(false);
    expect(unifiedMemoryEnabled({ values: {} })).toBe(false);
    expect(unifiedMemoryEnabled(null)).toBe(false);
    expect(unifiedMemoryEnabled({ values: { [UNIFIED_ENABLED_KEY]: { value: "true" } } })).toBe(false);
  });
});
