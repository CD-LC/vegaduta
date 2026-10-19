// Agent Lab i18n guard. useTranslations() is untyped and a missing key
// renders its own path ("agentLab.koRnu") instead of failing, so a typo in a
// key is invisible to tsc and to every test that mocks the translator. This
// reads the lab's sources and checks each t("…") literal against
// messages/en.json.
//
// It relies on the lab's convention (see the spec's i18n rules): inside
// app/components/agents/lab the identifier `t` is only ever the
// useTranslations("agentLab") translator, and it is only called with a
// double-quoted string literal - no template-built keys, no key held in a
// variable. A key built any other way is simply not seen here.

import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import en from "../../../../messages/en.json";

const LAB_DIR = path.resolve(process.cwd(), "app/components/agents/lab");
const KEY_RE = /\bt\(\s*"([A-Za-z0-9_]+)"/g;

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...sourceFiles(p));
    else if (/\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) out.push(p);
  }
  return out.sort();
}

/** key -> the lab files (relative to the lab folder) that use it. */
function collectUsedKeys(): Map<string, string[]> {
  const used = new Map<string, string[]>();
  for (const file of sourceFiles(LAB_DIR)) {
    const rel = path.relative(LAB_DIR, file).split(path.sep).join("/");
    for (const match of fs.readFileSync(file, "utf8").matchAll(KEY_RE)) {
      const files = used.get(match[1]) ?? [];
      if (!files.includes(rel)) files.push(rel);
      used.set(match[1], files);
    }
  }
  return used;
}

describe("agentLab i18n keys", () => {
  const catalog = (en as Record<string, unknown>).agentLab as Record<string, string> | undefined;

  it("has the agentLab namespace in messages/en.json", () => {
    expect(catalog).toBeTypeOf("object");
    expect(Object.keys(catalog ?? {}).length).toBeGreaterThan(0);
  });

  it("finds the lab's sources and the keys they use", () => {
    expect(sourceFiles(LAB_DIR).length).toBeGreaterThan(0);
    expect(collectUsedKeys().size).toBeGreaterThan(0);
  });

  it("every t(\"…\") key used under components/agents/lab exists in en.agentLab", () => {
    const used = collectUsedKeys();
    const missing = [...used.entries()]
      .filter(([key]) => !Object.prototype.hasOwnProperty.call(catalog ?? {}, key))
      .map(([key, files]) => `${key} (${files.join(", ")})`);
    expect(missing, `agentLab keys missing from messages/en.json:\n  ${missing.join("\n  ")}`).toEqual([]);

    // Informational only: while the panels are being built most keys are
    // still unused, and a key nobody reads is not a defect.
    const unused = Object.keys(catalog ?? {}).filter((key) => !used.has(key));
    if (unused.length > 0) {
      console.info(`[labI18nKeys] ${unused.length} agentLab keys are not used by any lab file yet: ${unused.join(", ")}`);
    }
  });
});
