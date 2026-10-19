// Every key the artifacts components ask for must exist in the "artifacts"
// namespace of messages/en.json: t("missing") returns the truthy dotted path,
// so a typo would ship as visible garbage rather than falling back.

import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import en from "../../../messages/en.json";

const DIR = __dirname;
const FILES = ["ArtifactPane.tsx", "ArtifactPreview.tsx", "ArtifactMessageBody.tsx", "useArtifactsPane.tsx"];

function usedKeys(): Set<string> {
  const keys = new Set<string>();
  for (const f of FILES) {
    const src = readFileSync(join(DIR, f), "utf8");
    for (const m of src.matchAll(/\bt\(\s*"([A-Za-z0-9_.]+)"/g)) keys.add(m[1]);
    // Dynamic lookups go through *_KEY tables whose values are plain string literals.
    for (const m of src.matchAll(/"((?:refused|kind)[A-Z][A-Za-z]*)"/g)) keys.add(m[1]);
  }
  return keys;
}

describe("artifacts i18n namespace", () => {
  const ns = (en as unknown as Record<string, Record<string, string>>).artifacts;

  it("exists", () => {
    expect(ns).toBeTruthy();
  });

  it("defines every key the components use", () => {
    const missing = [...usedKeys()].filter((k) => !(k in ns));
    expect(missing).toEqual([]);
  });

  it("covers every preview refusal reason and every kind label", () => {
    for (const k of ["refusedIncomplete", "refusedTruncated", "refusedTooLarge", "refusedEmpty", "refusedUnsafe", "refusedNotSvg"]) {
      expect(ns[k]).toBeTruthy();
    }
    for (const k of ["kindHtml", "kindSvg", "kindMarkdown", "kindCode", "kindMermaid", "kindCsv"]) {
      expect(ns[k]).toBeTruthy();
    }
  });

  it("keeps placeholders the components pass", () => {
    expect(ns.chipOpen).toContain("{title}");
    expect(ns.askPrefill).toContain("{title}");
    expect(ns.askPrefill).toContain("{n}");
    expect(ns.versionLabel).toContain("{n}");
  });
});
