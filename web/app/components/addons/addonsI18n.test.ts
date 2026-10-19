import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import messages from "../../../messages/en.json";
import { errorKeyFor, learnMoreHref } from "../../lib/addonsModel";

// Every string the add-ons page can show must exist in en.json (a miss renders the dotted path), and the
// keys that are built at run time (status, category, unlock, error, filter) are checked against the
// values core can send, including the real catalog when the repository is checked out around web/.

const here = path.dirname(fileURLToPath(import.meta.url));
const ns = (messages as unknown as Record<string, Record<string, unknown>>).addons;

function has(key: string): boolean {
  let cur: unknown = ns;
  for (const part of key.split(".")) {
    if (cur && typeof cur === "object" && part in (cur as object)) cur = (cur as Record<string, unknown>)[part];
    else return false;
  }
  return typeof cur === "string";
}

function literalKeys(): { file: string; key: string }[] {
  const out: { file: string; key: string }[] = [];
  for (const file of readdirSync(here).filter((f) => /\.tsx?$/.test(f) && !/\.test\.|fixtures/.test(f))) {
    const src = readFileSync(path.join(here, file), "utf8");
    // t("a.b", ...) and the quoted branches of t(cond ? "a" : "b"); template keys are checked below.
    for (const call of src.matchAll(/\bt\(([^()]*(?:\([^()]*\)[^()]*)*)\)/g)) {
      for (const lit of call[1].matchAll(/"([A-Za-z][A-Za-z0-9_.]*)"/g)) out.push({ file, key: lit[1] });
    }
  }
  return out;
}

describe("add-ons i18n", () => {
  it("has the namespace and the nav label", () => {
    expect(ns).toBeTruthy();
    expect((messages as unknown as { nav: Record<string, string> }).nav.railAddons).toBe("Add-ons");
  });

  it("defines every literal key the components use", () => {
    const keys = literalKeys();
    expect(keys.length).toBeGreaterThan(60);
    const missing = keys.filter((k) => !has(k.key)).map((k) => `${k.file}: ${k.key}`);
    expect(missing).toEqual([]);
  });

  it("defines the keys built at run time", () => {
    const dynamic = [
      ...["off", "enable_requested", "applying", "on", "disable_requested", "pulling", "failed", "unavailable"].map((s) => `status.${s}`),
      ...["assistant", "search", "documents", "models", "media", "tools"].flatMap((c) => [`category.${c}`, `categoryHint.${c}`]),
      ...["web_search", "documents", "transcription", "assistant_terminal", "browser_automation", "local_models", "mcp_tools", "image_generation", "speech"].map(
        (u) => `unlock.${u}`
      ),
      ...["all", "on", "off", "attention", "planned"].map((s) => `filter.status.${s}`),
      ...["low_memory", "needs_gpu"].map((f) => `fit.${f}`),
      ...["blocked.ollama_unreachable"],
      errorKeyFor(undefined, null),
      errorKeyFor(401, null),
      errorKeyFor(403, null),
      errorKeyFor(503, null),
      ...["unknown_addon", "not_available", "required_by", "conflict", "rate_limited", "too_many_downloads", "ollama_unreachable"].map((c) =>
        errorKeyFor(409, c)
      ),
    ];
    expect(dynamic.filter((k) => !has(k))).toEqual([]);
  });

  it("does not shadow the catalog's English text, which is the source for English", () => {
    expect((ns as Record<string, unknown>).items).toBeUndefined();
  });

  const catalog = path.resolve(here, "../../../../core/providers/src/main/resources/addons/catalog.json");
  it.skipIf(!existsSync(catalog))("covers every category, unlock key and doc link in the real catalog", () => {
    const doc = JSON.parse(readFileSync(catalog, "utf8")) as {
      addons: { id: string; category: string; unlocks: string[]; learnMore: string; availability: string; description: string }[];
    };
    for (const a of doc.addons) {
      expect(has(`category.${a.category}`), `${a.id} category ${a.category}`).toBe(true);
      expect(has(`categoryHint.${a.category}`), `${a.id} hint ${a.category}`).toBe(true);
      for (const u of a.unlocks) expect(has(`unlock.${u}`), `${a.id} unlock ${u}`).toBe(true);
      expect(learnMoreHref(a.learnMore), `${a.id} learnMore ${a.learnMore}`).not.toBeNull();
    }
  });
});
