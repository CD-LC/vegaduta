import { describe, expect, it } from "vitest";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import {
  MESSAGES_DIR,
  TODO_DOC,
  UI_NAMESPACES,
  analyse,
  controlUiKeys,
  fill,
  getAt,
  leaves,
  localeFiles,
  setAt,
  todoMarkdown,
} from "../../scripts/ui-i18n.mjs";

const en = { settingsUi: { save: "Save", nested: { a: "A" } }, other: { old: "Old", fresh: "Fresh" }, list: { l: ["x"] } };
const baseline = { other: { old: "Old" }, list: { l: ["x"] } };

describe("control UI key selection", () => {
  it("takes whole control namespaces plus keys added to other namespaces since the baseline", () => {
    expect(controlUiKeys(en, baseline, ["settingsUi"]).sort()).toEqual(["other.fresh", "settingsUi.nested.a", "settingsUi.save"]);
    expect(controlUiKeys(en, null, ["settingsUi"]).sort()).toEqual(["settingsUi.nested.a", "settingsUi.save"]);
  });
  it("treats arrays as leaves and skips pipeline metadata", () => {
    expect([...leaves({ a: { b: ["x"] }, _sourceHash: "h" }).keys()]).toEqual(["a.b"]);
  });
  it("reads and writes by dotted path", () => {
    const t: Record<string, unknown> = {};
    setAt(t, "a.b.c", "v");
    expect(getAt(t, "a.b.c")).toBe("v");
    expect(getAt(t, "a.x")).toBeUndefined();
  });
});

describe("fill and analyse on a scratch copy of the messages", () => {
  function scratch() {
    const dir = mkdtempSync(path.join(tmpdir(), "ui-i18n-"));
    writeFileSync(path.join(dir, "en.json"), JSON.stringify({ settingsUi: { save: "Save", nested: { a: "A" } }, nav: { x: "X" } }, null, 2));
    writeFileSync(path.join(dir, "xx.json"), JSON.stringify({ settingsUi: { save: "Sauvegarder" }, nav: { x: "Y" } }, null, 2) + "\n");
    return dir;
  }
  it("reports missing and still-English keys, fills only what is missing and keeps each file's ending", () => {
    const dir = scratch();
    const before = analyse({ dir, baseline: null });
    expect(before.locales.xx.missing).toEqual(["settingsUi.nested.a"]);
    expect(before.locales.xx.english).toEqual([]);

    expect(fill({ dir, ref: "none" })).toBe(1);
    const raw = readFileSync(path.join(dir, "xx.json"), "utf8");
    expect(raw.endsWith("}\n")).toBe(true);
    const parsed = JSON.parse(raw);
    expect(parsed.settingsUi.save).toBe("Sauvegarder"); // untouched
    expect(parsed.nav.x).toBe("Y"); // not a control namespace, untouched
    expect(parsed.settingsUi.nested.a).toBe("A");

    const after = analyse({ dir, baseline: null });
    expect(after.locales.xx.missing).toEqual([]);
    expect(after.locales.xx.english).toEqual(["settingsUi.nested.a"]);
    expect(fill({ dir, ref: "none" })).toBe(0);
  });
  it("lists untranslated keys in the todo markdown", () => {
    const dir = scratch();
    fill({ dir, ref: "none" });
    const md = todoMarkdown(analyse({ dir, baseline: null }));
    expect(md).toContain("| `settingsUi.nested.a` | A |");
    expect(md).not.toContain("| `settingsUi.save` |");
  });
});

describe("the repository's translations", () => {
  const result = analyse();

  it("has a locale file for every language and some control UI keys", () => {
    expect(localeFiles().length).toBeGreaterThanOrEqual(29);
    expect(result.keys.length).toBeGreaterThan(500);
    for (const ns of UI_NAMESPACES) expect(result.keys.some((k) => k.startsWith(`${ns}.`)), ns).toBe(true);
  });

  it("every control UI key exists in every locale file (English fallback until translated)", () => {
    const missing = Object.entries(result.locales)
      .filter(([, v]) => v.missing.length)
      .map(([l, v]) => `${l}: ${v.missing.length} missing, e.g. ${v.missing.slice(0, 3).join(", ")}`);
    expect(missing, "run `node scripts/ui-i18n.mjs --fill` from web/, then `--todo`").toEqual([]);
  });

  it("keeps the translation todo document", () => {
    expect(existsSync(TODO_DOC)).toBe(true);
    const doc = readFileSync(TODO_DOC, "utf8");
    for (const ns of UI_NAMESPACES) expect(doc).toContain(`### \`${ns}\``);
    expect(MESSAGES_DIR).toContain("messages");
  });
});
