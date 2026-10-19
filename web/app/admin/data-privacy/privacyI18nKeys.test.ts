// Every key the privacy and SSO UI asks for exists in en.json (a miss would render as its raw path).
import { readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";

const root = path.resolve(__dirname, "../..");
const files = [
  ...readdirSync(__dirname).filter((f) => f.endsWith(".tsx")).map((f) => path.join(__dirname, f)),
  ...readdirSync(path.join(root, "components/sso")).filter((f) => f.endsWith(".tsx")).map((f) => path.join(root, "components/sso", f)),
  path.join(root, "admin/sso/page.tsx"),
];
const ns = (en as unknown as Record<string, Record<string, string>>).privacySso;

describe("privacySso i18n keys", () => {
  const keys = new Set<string>();
  for (const f of files) {
    const src = readFileSync(f, "utf8");
    for (const m of src.matchAll(/\bt\(\s*"([A-Za-z_]+)"/g)) keys.add(m[1]);
    for (const m of src.matchAll(/"(err[A-Za-z]+)"/g)) keys.add(m[1]);
  }
  it("finds keys", () => expect(keys.size).toBeGreaterThan(100));
  it("has every static key", () => {
    expect([...keys].filter((k) => typeof ns[k] !== "string")).toEqual([]);
  });
  it("has the dynamic tab and class keys", () => {
    for (const k of ["consent", "retention", "purposes", "contacts", "audit", "workspace"]) expect(ns[`tab_${k}`]).toBeTruthy();
    for (const k of ["TRANSCRIPT", "RECORDING", "EXTRACTED_FIELDS"]) expect(ns[`class_${k}`]).toBeTruthy();
    for (const k of ["errAuditMissing", "errAuditOrder", "errAuditRange"]) expect(ns[k]).toBeTruthy();
  });
});
