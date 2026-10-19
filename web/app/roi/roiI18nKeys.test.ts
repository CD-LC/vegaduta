// Every key the ROI page reads must exist in messages/en.json's "roi" namespace: t() returns the dotted path for a
// missing key, so a typo would render "roi.xyz" on screen. Keys chosen at runtime are listed explicitly. The reverse
// is checked too: a key nothing reads is dead weight in every locale file.
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";

const ns = (en as unknown as Record<string, Record<string, string>>).roi;
const source = readFileSync(path.resolve(__dirname, "./page.tsx"), "utf8");
const tableSource = readFileSync(path.resolve(__dirname, "./AgentTable.tsx"), "utf8");
const model = readFileSync(path.resolve(__dirname, "./roiModel.ts"), "utf8");

const DYNAMIC = [
  "period7", "period30", "period90", // `period${days}`
  "rangeInvalid", "rangeOrder", "rangeTooLong", // validateRange()
  "roiNoteNoAssumptions", "roiNoteNoValue", "roiNoteCurrency", "roiNoteIncomplete", "roiNoteNoCost", // roiNoteKey()
];

function literalKeys(src: string): string[] {
  return [...new Set([...src.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)].map((m) => m[1]))];
}

describe("roi i18n", () => {
  it("has the namespace, and Insights links to the page", () => {
    expect(ns).toBeTypeOf("object");
    expect((en as unknown as Record<string, Record<string, string>>).insights.linkRoi).toBe("Cost & ROI");
  });

  it("defines every literal key the page uses and reads only the roi namespace", () => {
    const keys = literalKeys(source + tableSource);
    expect(keys.length).toBeGreaterThan(30);
    expect(keys.filter((k) => !(k in ns))).toEqual([]);
    expect([...source.matchAll(/useTranslations\(\s*"([\w.-]+)"/g)].map((m) => m[1])).toEqual(["roi"]);
  });

  it("defines every key chosen at runtime, and the model really returns them", () => {
    expect(DYNAMIC.filter((k) => !(k in ns))).toEqual([]);
    for (const k of DYNAMIC.filter((x) => x.startsWith("roiNote") || x.startsWith("range"))) {
      expect(model).toContain(`"${k}"`);
    }
  });

  it("has no unused key", () => {
    const used = new Set([...literalKeys(source + tableSource), ...DYNAMIC]);
    expect(Object.keys(ns).filter((k) => !used.has(k))).toEqual([]);
  });

  it("states the assumptions label on the ROI column in plain words", () => {
    expect(ns.colRoi).toBe("Estimated ROI");
    expect(ns.assumptionHelp).toContain("Your own numbers");
  });
});
