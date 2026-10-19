import { describe, expect, it } from "vitest";
import { linkCitations, normalizeSourceId, safeSourceUrl, sourceUrlMap } from "./citeLinks";

const SOURCES = [
  { id: "S1", url: "https://example.org/a" },
  { id: "S2", url: "http://example.com/b?x=1" },
  { id: "S3", url: "javascript:alert(1)" },
  { id: "S4", url: "https://en.wikipedia.org/wiki/Mercury_(planet)" },
];

describe("safeSourceUrl", () => {
  it("accepts http and https only", () => {
    expect(safeSourceUrl("https://example.org/a")).toBe("https://example.org/a");
    expect(safeSourceUrl("http://example.org/a")).toBe("http://example.org/a");
    expect(safeSourceUrl("javascript:alert(1)")).toBeNull();
    expect(safeSourceUrl("data:text/html,<b>x</b>")).toBeNull();
    expect(safeSourceUrl("/relative/path")).toBeNull();
    expect(safeSourceUrl("")).toBeNull();
    expect(safeSourceUrl(null)).toBeNull();
    expect(safeSourceUrl(42)).toBeNull();
  });

  it("encodes characters that would cut a markdown link short", () => {
    expect(safeSourceUrl("https://en.wikipedia.org/wiki/Mercury_(planet)")).toBe(
      "https://en.wikipedia.org/wiki/Mercury_%28planet%29"
    );
  });
});

describe("normalizeSourceId / sourceUrlMap", () => {
  it("normalises spacing and case", () => {
    expect(normalizeSourceId("s 3")).toBe("S3");
    expect(normalizeSourceId(undefined)).toBe("");
  });

  it("keeps only sources with a safe url, first id wins", () => {
    const map = sourceUrlMap([...SOURCES, { id: "S1", url: "https://other.example/" }]);
    expect(map.get("S1")).toBe("https://example.org/a");
    expect(map.has("S3")).toBe(false);
    expect(sourceUrlMap(null).size).toBe(0);
  });
});

describe("linkCitations", () => {
  it("rewrites a known marker to a markdown link", () => {
    expect(linkCitations("Water is wet [S1].", SOURCES)).toBe("Water is wet [S1](https://example.org/a).");
  });

  it("accepts a space inside the marker and lower case", () => {
    expect(linkCitations("x [S 2] y [s1]", SOURCES)).toBe(
      "x [S2](http://example.com/b?x=1) y [S1](https://example.org/a)"
    );
  });

  it("splits a grouped marker and leaves unknown ids inert inside it", () => {
    expect(linkCitations("claim [S1, S9; S2]", SOURCES)).toBe(
      "claim [S1](https://example.org/a), [S9], [S2](http://example.com/b?x=1)"
    );
  });

  it("leaves an unknown id untouched", () => {
    expect(linkCitations("claim [S9]", SOURCES)).toBe("claim [S9]");
  });

  it("refuses a non-http source url", () => {
    expect(linkCitations("claim [S3]", SOURCES)).toBe("claim [S3]");
  });

  it("does not double-link a marker the model already linked", () => {
    const text = "claim [S1](https://example.org/a)";
    expect(linkCitations(text, SOURCES)).toBe(text);
  });

  it("does not touch image syntax, inline code or fenced code", () => {
    expect(linkCitations("![S1] and `[S1]`", SOURCES)).toBe("![S1] and `[S1]`");
    const fenced = "```\n[S1]\n```\nafter [S1]";
    expect(linkCitations(fenced, SOURCES)).toBe("```\n[S1]\n```\nafter [S1](https://example.org/a)");
  });

  it("encodes parentheses so the link target survives", () => {
    expect(linkCitations("[S4]", SOURCES)).toBe("[S4](https://en.wikipedia.org/wiki/Mercury_%28planet%29)");
  });

  it("tolerates empty input", () => {
    expect(linkCitations(null, SOURCES)).toBe("");
    expect(linkCitations("text [S1]", [])).toBe("text [S1]");
    expect(linkCitations("text [S1]", undefined)).toBe("text [S1]");
  });
});
