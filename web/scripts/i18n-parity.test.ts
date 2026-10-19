import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { compare, flatten, prune, ratchet, serialize, analyse, MESSAGES_DIR } from "./i18n-parity.mjs";

const en = { a: { x: "1", y: "2", n: { z: "3" } }, b: "4", list: ["p"] };

describe("i18n parity core", () => {
  it("flattens to leaf paths", () => {
    expect([...flatten(en)].sort()).toEqual(["a.n.z", "a.x", "a.y", "b", "list"]);
  });

  it("computes coverage and dead keys", () => {
    const loc = { a: { x: "x", gone: "g", n: { z: "z", old: "o" } }, dead: { k: "v" } };
    const r = compare(en, loc);
    expect(r.enKeys).toBe(5);
    expect(r.present).toBe(2);
    expect(r.coverage).toBe(40);
    expect(r.dead).toBe(3);
    expect(r.deadKeys.sort()).toEqual(["a.gone", "a.n.old", "dead.k"]);
  });

  it("prunes dead keys, keeps order and drops emptied namespaces", () => {
    const loc = { b: "B", a: { gone: "g", x: "X", n: { old: "o" } }, dead: { k: "v" }, list: ["P"] };
    const out = prune(en, loc);
    expect(out).toEqual({ b: "B", a: { x: "X" }, list: ["P"] });
    expect(Object.keys(out)).toEqual(["b", "a", "list"]);
    expect(compare(en, out).dead).toBe(0);
  });

  it("prune is idempotent and handles leaf/tree shape mismatches", () => {
    const loc = { a: "now a leaf locale-side", b: { sub: "tree where en has a leaf" } };
    const once = prune(en, loc);
    expect(once).toEqual({});
    expect(prune(en, once)).toEqual(once);
  });

  it("ratchet fails on lower coverage or more dead keys, passes otherwise", () => {
    const base = { de: { coverage: 50, dead: 5 } };
    expect(ratchet(base, { de: { coverage: 50, dead: 5 } })).toEqual([]);
    expect(ratchet(base, { de: { coverage: 60, dead: 0 } })).toEqual([]);
    expect(ratchet(base, { de: { coverage: 49, dead: 5 } })).toHaveLength(1);
    expect(ratchet(base, { de: { coverage: 50, dead: 6 } })).toHaveLength(1);
    expect(ratchet(base, { de: { coverage: 50, dead: 5 }, fr: { coverage: 1, dead: 0 } })).toHaveLength(1);
    expect(ratchet(base, {})).toHaveLength(1);
  });

  it("serialize matches the on-disk locale format", () => {
    expect(serialize({ a: "b" })).toBe('{\n  "a": "b"\n}\n');
  });
});

describe("committed locale files", () => {
  it("parse, round-trip byte for byte and only use namespaces that exist in en.json", () => {
    const { en: enTree, stats } = analyse();
    expect(Object.keys(stats).length).toBeGreaterThan(20);
    for (const loc of Object.keys(stats)) {
      const file = path.join(MESSAGES_DIR, `${loc}.json`);
      const text = readFileSync(file, "utf8");
      expect(serialize(JSON.parse(text))).toBe(text);
      for (const ns of Object.keys(JSON.parse(text)).filter((k) => !k.startsWith("_"))) expect(enTree).toHaveProperty([ns]); // "_sourceHash" etc. are pipeline metadata
    }
    expect(existsSync(path.join(MESSAGES_DIR, "i18n-parity-baseline.json"))).toBe(true);
  });
});
