import { describe, expect, it } from "vitest";
import { parseDiff, speechLang, suggestionsFor } from "./suggestions";

describe("suggestionsFor", () => {
  it("returns at most four chips with real prompts", () => {
    const s = suggestionsFor("static-site", ["index.html"]);
    expect(s.length).toBeLessThanOrEqual(4);
    expect(s.map((x) => x.id)).toContain("dark-mode");
    expect(s.every((x) => x.prompt.length > 10)).toBe(true);
  });
  it("hides ideas the project already has", () => {
    const s = suggestionsFor("static-site", ["src/dark.css", "contact.html"], [], 20);
    expect(s.map((x) => x.id)).not.toContain("dark-mode");
    expect(s.map((x) => x.id)).not.toContain("contact-form");
  });
  it("hides ideas already asked and web-only chips for native templates", () => {
    const first = suggestionsFor("capacitor-app", [], [], 20);
    expect(first.map((x) => x.id)).not.toContain("seo");
    const again = suggestionsFor("capacitor-app", [], [first[0].prompt], 20);
    expect(again.map((x) => x.id)).not.toContain(first[0].id);
  });
  it("tolerates missing input", () => {
    expect(suggestionsFor(null, null).length).toBeGreaterThan(0);
  });
});

describe("parseDiff", () => {
  it("classifies lines and truncates", () => {
    const d = "--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n same\n";
    const { lines, truncated } = parseDiff(d);
    expect(lines.map((l) => l.kind)).toEqual(["meta", "meta", "hunk", "del", "add", "ctx"]);
    expect(truncated).toBe(false);
    expect(parseDiff("+a\n+b\n+c", 2)).toEqual({
      lines: [
        { kind: "add", text: "+a" },
        { kind: "add", text: "+b" },
      ],
      truncated: true,
    });
    expect(parseDiff("").lines).toEqual([]);
  });
});

describe("speechLang", () => {
  it("maps document language to a recognition locale", () => {
    expect(speechLang("hi")).toBe("hi-IN");
    expect(speechLang("en")).toBe("en-US");
    expect(speechLang("pt-PT")).toBe("pt-PT");
    expect(speechLang("")).toBe("en-US");
    expect(speechLang("xx")).toBe("xx");
  });
});
