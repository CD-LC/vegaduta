// Every t("...") the unified-memory panel and row read must exist in messages/en.json's "memory"
// namespace (literal keys) or be a key the labelKey() helper can build (kind_/status_/trust_/event_).

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";

const ns = (en as any).memory as Record<string, string>;
const pageNs = (en as any).memoryPage as Record<string, string>;

const FILES = ["./MemoryItemsPanel.tsx", "./MemoryItemRow.tsx"];

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  return [...keys];
}

describe("memory i18n (unified items)", () => {
  it("has the namespace", () => {
    expect(ns).toBeTruthy();
  });

  for (const file of FILES) {
    it(`defines every literal key used by ${file}`, () => {
      const source = readFileSync(path.resolve(__dirname, file), "utf8");
      const keys = literalKeys(source);
      expect(keys.length).toBeGreaterThan(0);
      expect(keys.filter((k) => !(k in ns))).toEqual([]);
    });
  }

  it("defines every key labelKey() can build", () => {
    const dynamic = [
      ...["EPISODIC", "SEMANTIC", "PROCEDURAL", "SELF_MODEL", "other"].map((v) => `kind_${v}`),
      ...["PENDING", "ACTIVE", "REJECTED", "SUPERSEDED", "REVERTED", "other"].map((v) => `status_${v}`),
      ...["TRUSTED", "QUARANTINED", "other"].map((v) => `trust_${v}`),
      ...[
        "CREATED",
        "APPROVED",
        "REJECTED",
        "EDITED",
        "REVERTED",
        "EXPIRED",
        "ERASED",
        "PINNED",
        "REFUSED",
        "other",
      ].map((v) => `event_${v}`),
      "group_review",
      "group_review_hint",
      "group_active",
      "group_active_hint",
      "group_tombstoned",
      "group_tombstoned_hint",
    ];
    expect(dynamic.filter((k) => !(k in ns))).toEqual([]);
  });

  it("defines the memoryPage tab keys for the items tab", () => {
    expect(pageNs.tab_items).toBeTruthy();
    expect(pageNs.tab_items_purpose).toBeTruthy();
  });

  it("keeps the placeholders the code passes values for", () => {
    expect(ns.writerIs).toContain("{writer}");
    expect(ns.savedOn).toContain("{date}");
    expect(ns.subjects).toContain("{subjects}");
    expect(ns.eventTransition).toContain("{from}");
    expect(ns.eventTransition).toContain("{to}");
  });
});
