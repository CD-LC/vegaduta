import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";
import {
  MEMORY_TABS,
  notesAreUnused,
  optedInAgentCount,
  resolveTab,
  tabFromHash,
  writtenNoteCount,
} from "./memoryTabs";
import type { MemoryAgent, MemoryBlock } from "./memoryTypes";

const block = (label: string, content: string): MemoryBlock => ({ label, content }) as MemoryBlock;
const agent = (id: string, on: boolean): MemoryAgent => ({ id, name: id, shareMemoryWithTenant: on, editable: true });

describe("tabFromHash", () => {
  it("reads a tab id from a hash, case-insensitively", () => {
    expect(tabFromHash("#facts")).toBe("facts");
    expect(tabFromHash("#Privacy")).toBe("privacy");
    expect(tabFromHash("assistants")).toBe("assistants");
  });
  it("ignores anything that is not a tab", () => {
    expect(tabFromHash("#nope")).toBeNull();
    expect(tabFromHash("")).toBeNull();
    expect(tabFromHash(null)).toBeNull();
  });
});

describe("resolveTab", () => {
  it("keeps a requested tab that is available", () => {
    expect(resolveTab("facts", ["notes", "facts"])).toBe("facts");
  });
  it("falls back to the first available tab", () => {
    expect(resolveTab("privacy", ["facts", "import"])).toBe("facts");
    expect(resolveTab(null, ["import"])).toBe("import");
    expect(resolveTab("notes", [])).toBeNull();
  });
});

describe("counts", () => {
  it("counts only non-empty notes and ignores the active-project pointer", () => {
    const blocks = [block("persona", "Be brief"), block("user_profile", "  "), block("project:acme", "x"), block("active_project", "acme")];
    expect(writtenNoteCount(blocks)).toBe(2);
    expect(writtenNoteCount(null)).toBe(0);
  });
  it("counts assistants that opted in", () => {
    expect(optedInAgentCount([agent("a", true), agent("b", false), agent("c", true)])).toBe(2);
    expect(optedInAgentCount(undefined)).toBe(0);
  });
});

describe("notesAreUnused", () => {
  const notes = [block("persona", "Be brief")];
  it("is true only when notes exist and no assistant reads them", () => {
    expect(notesAreUnused(notes, [agent("a", false)])).toBe(true);
    expect(notesAreUnused(notes, [agent("a", true)])).toBe(false);
    expect(notesAreUnused([], [agent("a", false)])).toBe(false);
  });
  it("stays quiet while agents are loading or there are none", () => {
    expect(notesAreUnused(notes, null)).toBe(false);
    expect(notesAreUnused(notes, [])).toBe(false);
  });
});

describe("memoryPage i18n (tabs)", () => {
  const ns = (en as any).memoryPage as Record<string, string>;
  it("defines a label and a one-line purpose for every tab", () => {
    const missing: string[] = [];
    for (const tab of MEMORY_TABS) {
      for (const key of [`tab_${tab}`, `tab_${tab}_purpose`]) if (!(key in ns)) missing.push(key);
    }
    expect(missing).toEqual([]);
  });
  it("defines every literal key memoryTabs-aware UI reads", () => {
    const src = readFileSync(path.resolve(__dirname, "./page.tsx"), "utf8");
    const keys = [...src.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)].map((m) => m[1]);
    expect(keys.filter((k) => !(k in ns))).toEqual([]);
  });
});
