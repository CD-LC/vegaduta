import { describe, expect, it } from "vitest";
import {
  NOT_A_WEB_ADDRESS,
  cleanHeading,
  deriveFollowUps,
  domainSummary,
  filterGroups,
  groupIndexById,
  groupSources,
  quoteToneCounts,
  readingMinutes,
  slugify,
  splitReport,
  toFootnotedMarkdown,
  urlKey,
} from "./reportStructure";
import { DEFAULT_EXPORT_LABELS } from "./exportReport";

describe("splitReport", () => {
  it("splits intro and sections, dropping a leading H1 that repeats the title", () => {
    const md = "# Tea and health\n\nIntro [S1].\n\n## Findings\nA\n\n### Detail\nB\n\n## Limits\nC";
    const o = splitReport(md, "Tea and health");
    expect(o.intro).toBe("Intro [S1].");
    expect(o.sections.map((s) => [s.level, s.heading])).toEqual([
      [2, "Findings"],
      [3, "Detail"],
      [2, "Limits"],
    ]);
    expect(o.sections[0].body).toBe("A");
  });

  it("keeps a different H1, strips markup and markers from headings, and makes ids unique", () => {
    const o = splitReport("# Other\nx\n## **Bold** [S1] [link](https://a.example)\ny\n## Bold link\nz", "Title");
    expect(o.sections[0].heading).toBe("Other");
    expect(o.sections[1].heading).toBe("Bold link");
    expect(o.sections[2].heading).toBe("Bold link");
    expect(new Set(o.sections.map((s) => s.id)).size).toBe(3);
  });

  it("ignores headings inside fenced code and tolerates CRLF", () => {
    const o = splitReport("## A\r\n```\r\n## not a heading\r\n```\r\ntext", null);
    expect(o.sections).toHaveLength(1);
    expect(o.sections[0].body).toContain("## not a heading");
  });

  it("handles empty input", () => {
    expect(splitReport("", "T")).toEqual({ intro: "", sections: [] });
    expect(splitReport(undefined)).toEqual({ intro: "", sections: [] });
  });
});

describe("small helpers", () => {
  it("cleanHeading and slugify", () => {
    expect(cleanHeading("Costs [S1, S2] and `code`")).toBe("Costs and code");
    expect(slugify("Prix & Qualité")).toBe("prix-qualité");
    expect(slugify("!!!")).toBe("section");
  });
  it("readingMinutes is at least one", () => {
    expect(readingMinutes("")).toBe(1);
    expect(readingMinutes("word ".repeat(221))).toBe(2);
  });
});

describe("deriveFollowUps", () => {
  const outline = splitReport(
    `## Summary\n${"x ".repeat(60)}\n## Pricing models\n${"y ".repeat(60)}\n## Tiny\nshort\n## Pricing models\n${"z ".repeat(60)}`,
    null
  );
  it("puts unanswered first, skips generic/short/duplicate headings, never exceeds max", () => {
    const f = deriveFollowUps(outline, ["How much is too much?", "  "], "tea?", 6);
    expect(f.map((x) => x.kind)).toEqual(["unanswered", "section"]);
    expect(f[0].prompt).toContain("How much is too much?");
    expect(f[0].prompt).toContain("tea?");
    expect(f[1].label).toBe("Pricing models");
    expect(deriveFollowUps(outline, ["a", "b", "c"], "q", 2)).toHaveLength(2);
  });
});

describe("source grouping", () => {
  const sources = [
    { id: "S1", url: "https://www.Example.org/tea/?utm_source=x#frag", title: "Tea", quotes: [{ quote: "a", verdict: "SUPPORTED" }] },
    { id: "S2", url: "https://example.org/tea", quotes: [{ quote: "a", verdict: "SUPPORTED" }, { quote: "b", verdict: "NOT_SUPPORTED" }] },
    { id: "S3", url: "https://other.example/x", quotes: [{ quote: "c" }] },
    { id: "S4", url: "javascript:alert(1)" },
  ];
  const groups = groupSources(sources);

  it("dedupes the same page across spellings, keeping ids and unique quotes", () => {
    expect(urlKey(sources[0].url)).toBe(urlKey(sources[1].url));
    expect(groups).toHaveLength(3);
    expect(groups[0].ids).toEqual(["S1", "S2"]);
    expect(groups[0].quotes.map((q) => q.quote)).toEqual(["a", "b"]);
    expect(groups[0].title).toBe("Tea");
    expect(groups[2].domain).toBe(NOT_A_WEB_ADDRESS);
  });

  it("summarises domains and citation checks", () => {
    expect(domainSummary(groups)[0]).toMatchObject({ domain: "example.org", count: 1 });
    expect(quoteToneCounts(groups)).toEqual({ supported: 1, weak: 0, unsupported: 1, unknown: 1 });
  });

  it("filters by domain and tone, narrowing the shown quotes", () => {
    expect(filterGroups(groups, { domain: "other.example" })).toHaveLength(1);
    const bad = filterGroups(groups, { tone: "unsupported" });
    expect(bad).toHaveLength(1);
    expect(bad[0].quotes).toHaveLength(1);
    expect(filterGroups(groups, {})).toHaveLength(3);
  });

  it("maps every id to its group", () => {
    const m = groupIndexById(groups);
    expect(m.get("S1")).toBe(0);
    expect(m.get("S2")).toBe(0);
    expect(m.get("S3")).toBe(1);
  });
});

describe("toFootnotedMarkdown", () => {
  const result = {
    title: "Tea",
    reportMarkdown: "Tea is drunk [S1]. Mixed [S1, S2]. Unknown [S9]. `[S1]` stays in code.",
    sources: [
      { id: "S1", url: "https://a.example/t", title: "Tea facts", quotes: [{ quote: "most\nconsumed", verdict: "SUPPORTED" }] },
      { id: "S2", url: "https://b.example/" },
    ],
    unanswered: ["How much?"],
    partial: true,
  };
  const md = toFootnotedMarkdown(result, DEFAULT_EXPORT_LABELS);

  it("turns markers into footnote references and defines each source", () => {
    expect(md).toContain("Tea is drunk [^S1].");
    expect(md).toContain("Mixed [^S1][^S2].");
    expect(md).toContain("Unknown [S9].");
    expect(md).toContain("`[S1]` stays in code.");
    expect(md).toContain('[^S1]: Tea facts. https://a.example/t Quoted: "most consumed" (SUPPORTED)');
    expect(md).toContain("[^S2]: https://b.example/");
  });

  it("keeps the title, partial note and unanswered list", () => {
    expect(md.startsWith("# Tea\n")).toBe(true);
    expect(md).toContain("> Partial report");
    expect(md).toContain("## Not answered\n\n- How much?");
  });
});
