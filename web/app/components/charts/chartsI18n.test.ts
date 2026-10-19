// i18n guard for the chart kit. useTranslations() is untyped and a missing key
// renders its own path instead of failing, so this reads the kit's sources and
// checks every t("...") literal against the `charts` namespace in en.json -
// and that the namespace carries nothing the kit does not use.

import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";

const KEY_RE = /\bt\(\s*"([A-Za-z0-9_]+)"/g;
const NS_RE = /useTranslations\(\s*"([\w.-]+)"/g;
const charts = (en as unknown as Record<string, Record<string, string>>).charts;

const DIRS = ["app/components/charts", "app/components/explainers"];
// In explainers/ only FlowExplainer is part of the kit. The feature explainers
// built on it (WaitNodeExplainer, ...) carry their own copy in their own
// namespace and have their own keys test.
const KIT_EXPLAINERS = new Set(["FlowExplainer.tsx"]);
const sources = DIRS.flatMap((dir) =>
  fs
    .readdirSync(path.resolve(process.cwd(), dir))
    .filter((name) => /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name))
    .filter((name) => dir !== "app/components/explainers" || KIT_EXPLAINERS.has(name))
    .map((name) => ({ file: `${dir}/${name}`, source: fs.readFileSync(path.resolve(process.cwd(), dir, name), "utf8") }))
);

describe("charts namespace", () => {
  it("exists in en.json with the five built-in strings", () => {
    expect(charts).toBeTypeOf("object");
    expect(Object.keys(charts).sort()).toEqual(["andMore", "howThisWorks", "noData", "viewAsChart", "viewAsTable"]);
    expect(charts.andMore).toContain("{count}");
  });

  it("is the only namespace the kit reads", () => {
    const namespaces = new Set(sources.flatMap(({ source }) => [...source.matchAll(NS_RE)].map((m) => m[1])));
    expect([...namespaces]).toEqual(["charts"]);
  });

  it("every charts.* key the kit uses exists, and every key is used", () => {
    const used = new Set<string>();
    for (const { file, source } of sources) {
      for (const match of source.matchAll(KEY_RE)) {
        used.add(match[1]);
        expect(charts[match[1]], `charts.${match[1]} (${file})`).toBeTypeOf("string");
      }
    }
    expect(used.size).toBeGreaterThan(0);
    expect([...used].sort()).toEqual(Object.keys(charts).sort());
  });

  it("contains no em dash in the built-in strings", () => {
    for (const value of Object.values(charts)) expect(value).not.toMatch(/—/);
  });
});
