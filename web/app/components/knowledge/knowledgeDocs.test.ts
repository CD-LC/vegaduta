import { describe, expect, it } from "vitest";
import {
  chunksAsContext,
  countStatusGroups,
  filterDocs,
  parseRecentSearches,
  pushRecentSearch,
  sortDocs,
  statusGroup,
} from "./knowledgeDocs";

const d = (filename: string, status: string, chunkCount: number | null, createdAt: string | null) => ({
  filename,
  status,
  chunkCount,
  createdAt,
});
const docs = [
  d("b.pdf", "READY", 4, "2026-01-02T00:00:00Z"),
  d("a.md", "EMBEDDING", null, "2026-03-01T00:00:00Z"),
  d("c.png", "FAILED", 0, null),
];
const names = (xs: { filename: string }[]) => xs.map((x) => x.filename);

describe("knowledge docs", () => {
  it("treats every non-terminal status as processing", () => {
    expect(["UPLOADED", "QUEUED", "EXTRACTING", "EMBEDDING"].map(statusGroup)).toEqual(Array(4).fill("processing"));
    expect(countStatusGroups(docs)).toEqual({ all: 3, ready: 1, processing: 1, failed: 1 });
  });
  it("filters by status and filename", () => {
    expect(names(filterDocs(docs, "failed", ""))).toEqual(["c.png"]);
    expect(names(filterDocs(docs, "all", " PDF "))).toEqual(["b.pdf"]);
  });
  it("sorts without mutating; undated sorts last when newest", () => {
    expect(names(sortDocs(docs, "newest"))).toEqual(["a.md", "b.pdf", "c.png"]);
    expect(names(sortDocs(docs, "name"))).toEqual(["a.md", "b.pdf", "c.png"]);
    expect(names(sortDocs(docs, "chunks"))).toEqual(["b.pdf", "a.md", "c.png"]);
    expect(names(docs)[0]).toBe("b.pdf");
  });
});

describe("search history", () => {
  it("dedupes case-insensitively, caps at five, ignores blanks", () => {
    expect(pushRecentSearch(["refund policy", "sla"], "  Refund   Policy ")).toEqual(["Refund Policy", "sla"]);
    expect(pushRecentSearch(["a", "b", "c", "d", "e"], "f")).toEqual(["f", "a", "b", "c", "d"]);
    expect(pushRecentSearch(["a"], "   ")).toEqual(["a"]);
  });
  it("parses stored history defensively", () => {
    expect(parseRecentSearches(null)).toEqual([]);
    expect(parseRecentSearches("not json")).toEqual([]);
    expect(parseRecentSearches('{"a":1}')).toEqual([]);
    expect(parseRecentSearches('["x", 3, " "]')).toEqual(["x"]);
  });
  it("formats chunks as numbered context", () => {
    expect(chunksAsContext([{ source: "Docs #0", content: " hello " }, { source: "Docs #1", content: "bye" }])).toBe(
      "[1] Docs #0\nhello\n\n[2] Docs #1\nbye"
    );
  });
});
