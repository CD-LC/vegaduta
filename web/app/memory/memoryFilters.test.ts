import { describe, expect, it } from "vitest";
import {
  EMPTY_FACT_FILTER,
  activeProjectSlug,
  confirmationMatches,
  factAgentIds,
  filterFacts,
  hasWrittenNotes,
  isFactFilterActive,
  projectBlocks,
  projectSlug,
  sortFacts,
} from "./memoryFilters";
import type { MemoryBlock, MemoryFact } from "./memoryTypes";

const FACTS: MemoryFact[] = [
  { id: "1", scope: "agent", agentId: "a1", content: "Prefers short answers" },
  { id: "2", scope: "agent", agentId: "a2", content: "Invoices go out on the 1st" },
  { id: "3", scope: "shared", agentId: null, content: "Works in Bengaluru, prefers INR" },
];

describe("filterFacts", () => {
  it("returns everything for the empty filter and tolerates a missing list", () => {
    expect(filterFacts(FACTS, EMPTY_FACT_FILTER)).toHaveLength(3);
    expect(filterFacts(null, EMPTY_FACT_FILTER)).toEqual([]);
    expect(filterFacts(undefined, EMPTY_FACT_FILTER)).toEqual([]);
  });

  it("searches the text case-insensitively and ignores surrounding spaces", () => {
    expect(filterFacts(FACTS, { ...EMPTY_FACT_FILTER, query: "  PREFERS " }).map((f) => f.id)).toEqual(["1", "3"]);
    expect(filterFacts(FACTS, { ...EMPTY_FACT_FILTER, query: "nothing like this" })).toEqual([]);
  });

  it("filters by scope", () => {
    expect(filterFacts(FACTS, { ...EMPTY_FACT_FILTER, scope: "shared" }).map((f) => f.id)).toEqual(["3"]);
    expect(filterFacts(FACTS, { ...EMPTY_FACT_FILTER, scope: "agent" }).map((f) => f.id)).toEqual(["1", "2"]);
  });

  it("filters by agent, which also hides shared facts", () => {
    expect(filterFacts(FACTS, { ...EMPTY_FACT_FILTER, agentId: "a2" }).map((f) => f.id)).toEqual(["2"]);
  });

  it("combines search, scope and agent", () => {
    expect(filterFacts(FACTS, { query: "prefers", scope: "agent", agentId: "a1" }).map((f) => f.id)).toEqual(["1"]);
    expect(filterFacts(FACTS, { query: "prefers", scope: "agent", agentId: "a2" })).toEqual([]);
  });

  it("does not mutate its input", () => {
    const copy = [...FACTS];
    filterFacts(FACTS, { ...EMPTY_FACT_FILTER, query: "x" });
    expect(FACTS).toEqual(copy);
  });
});

describe("isFactFilterActive", () => {
  it("is false only for the empty filter", () => {
    expect(isFactFilterActive(EMPTY_FACT_FILTER)).toBe(false);
    expect(isFactFilterActive({ ...EMPTY_FACT_FILTER, query: "   " })).toBe(false);
    expect(isFactFilterActive({ ...EMPTY_FACT_FILTER, query: "a" })).toBe(true);
    expect(isFactFilterActive({ ...EMPTY_FACT_FILTER, scope: "shared" })).toBe(true);
    expect(isFactFilterActive({ ...EMPTY_FACT_FILTER, agentId: "a1" })).toBe(true);
  });
});

describe("factAgentIds", () => {
  it("lists each agent with a private fact once, skipping shared facts", () => {
    expect(factAgentIds([...FACTS, { id: "4", scope: "agent", agentId: "a1", content: "again" }])).toEqual(["a1", "a2"]);
    expect(factAgentIds(null)).toEqual([]);
  });
});

describe("project helpers", () => {
  const blocks: MemoryBlock[] = [
    { label: "persona", content: "Be brief" },
    { label: "active_project", content: "acme" },
    { label: "project:acme", content: "Acme notes" },
    { label: "project:globex", content: "Globex notes" },
  ];

  it("picks out the named project notes and their slugs", () => {
    expect(projectBlocks(blocks).map((b) => projectSlug(b.label))).toEqual(["acme", "globex"]);
    expect(projectBlocks(null)).toEqual([]);
    expect(projectSlug("persona")).toBe("persona");
  });

  it("reads the active project, tolerating a prefixed or upper-case pointer", () => {
    expect(activeProjectSlug(blocks)).toBe("acme");
    expect(activeProjectSlug([{ label: "active_project", content: "project:Globex" }, ...blocks.slice(2)])).toBe("globex");
  });

  it("reports no active project when the pointer is missing, blank or dangling", () => {
    expect(activeProjectSlug(blocks.filter((b) => b.label !== "active_project"))).toBeNull();
    expect(activeProjectSlug([{ label: "active_project", content: "  " }, ...blocks.slice(2)])).toBeNull();
    expect(activeProjectSlug([{ label: "active_project", content: "gone" }, ...blocks.slice(2)])).toBeNull();
    expect(activeProjectSlug(undefined)).toBeNull();
  });

  it("does not count the active-project pointer as a written note", () => {
    expect(hasWrittenNotes([{ label: "active_project", content: "acme" }])).toBe(false);
    expect(hasWrittenNotes([{ label: "persona", content: "   " }])).toBe(false);
    expect(hasWrittenNotes(blocks)).toBe(true);
    expect(hasWrittenNotes(null)).toBe(false);
  });
});

describe("confirmationMatches", () => {
  it("accepts the expected word regardless of case and spacing, and nothing else", () => {
    expect(confirmationMatches(" Forget ", "forget")).toBe(true);
    expect(confirmationMatches("forge", "forget")).toBe(false);
    expect(confirmationMatches("", "forget")).toBe(false);
    expect(confirmationMatches("", "")).toBe(false);
  });
});

describe("sortFacts", () => {
  const f = (id: string, createdAt: string | null, useCount?: number) =>
    ({ id, scope: "shared", content: id, createdAt, useCount }) as any;
  const facts = [f("a", "2026-01-02T00:00:00Z", 2), f("b", "2026-03-01T00:00:00Z", 0), f("c", null, 5)];
  const ids = (xs: any[]) => xs.map((x) => x.id);

  it("orders by date, undated last when newest-first", () => {
    expect(ids(sortFacts(facts, "newest"))).toEqual(["b", "a", "c"]);
    expect(ids(sortFacts(facts, "oldest"))).toEqual(["c", "a", "b"]);
  });
  it("orders by use count", () => {
    expect(ids(sortFacts(facts, "mostUsed"))).toEqual(["c", "a", "b"]);
    expect(ids(sortFacts(facts, "neverUsed"))).toEqual(["b", "a", "c"]);
  });
  it("does not mutate the input", () => {
    sortFacts(facts, "oldest");
    expect(ids(facts)).toEqual(["a", "b", "c"]);
  });
});
