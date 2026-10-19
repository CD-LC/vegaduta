import { describe, expect, it } from "vitest";
import { fetchSnippet, isDirty, maskKey, parseOrigins, policiesPayload, riskNotes } from "./dataApi";
import type { AppBuilderDataTable } from "./lib";

const table = (name: string, policy: Partial<Record<"select" | "insert" | "update" | "delete", boolean>> = {}): AppBuilderDataTable => ({
  name,
  columns: [{ name: "id", type: "integer", nullable: false }],
  policy: { select: false, insert: false, update: false, delete: false, ...policy },
});

describe("dataApi helpers", () => {
  it("policiesPayload keeps only tables with an operation on", () => {
    const p = policiesPayload([table("notes", { select: true }), table("secrets")]);
    expect(Object.keys(p)).toEqual(["notes"]);
    expect(p.notes).toEqual({ select: true, insert: false, update: false, delete: false });
  });

  it("parseOrigins normalises, dedupes, sorts and flags invalid entries", () => {
    const r = parseOrigins("https://My.App/, http://localhost:3000  https://my.app javascript:alert(1) https://x.com/path");
    expect(r.origins).toEqual(["http://localhost:3000", "https://my.app"]);
    expect(r.invalid).toEqual(["javascript:alert(1)", "https://x.com/path"]);
    expect(parseOrigins("").origins).toEqual([]);
  });

  it("parseOrigins enforces the server's 20 origin cap", () => {
    const many = Array.from({ length: 21 }, (_, i) => `https://a${i}.example.com`).join(",");
    expect(parseOrigins(many).invalid.length).toBe(1);
  });

  it("isDirty detects policy and origin edits only", () => {
    const saved = [table("notes", { select: true })];
    expect(isDirty(saved, [table("notes", { select: true })], [], "")).toBe(false);
    expect(isDirty(saved, [table("notes", { select: true, insert: true })], [], "")).toBe(true);
    expect(isDirty(saved, saved, [], "https://a.app")).toBe(true);
    expect(isDirty(saved, saved, ["https://a.app"], "https://A.app/")).toBe(false);
    // a table with everything off is the same as an absent table
    expect(isDirty(saved, [...saved, table("secrets")], [], "")).toBe(false);
    // an unusable entry is still an unsaved edit (so Save can explain why it fails)
    expect(isDirty(saved, saved, [], "javascript:alert(1)")).toBe(true);
  });

  it("riskNotes warns on update/delete and says nothing for plain read-only", () => {
    expect(riskNotes(table("n", { select: true }))).toEqual([]);
    expect(riskNotes(table("n", { select: true, insert: true }))).toEqual([]);
    expect(riskNotes(table("n", { select: true, delete: true })).join(" ")).toMatch(/delete rows/);
    expect(riskNotes(table("n", { update: true })).join(" ")).toMatch(/change any row/);
  });

  it("fetchSnippet embeds the URL and key as JSON string literals (no injection via a key)", () => {
    const snip = fetchSnippet('https://b.example/data/v1', 'vdk_abc"; alert(1); //', [table("notes", { select: true, insert: true })]);
    expect(snip).toContain('const VD_URL = "https://b.example/data/v1";');
    expect(snip).toContain('const VD_KEY = "vdk_abc\\"; alert(1); //";');
    expect(snip).toContain('"/notes?order=id.desc&limit=20"');
    expect(snip).toContain("// Create:");
  });

  it("fetchSnippet only shows the operations the table allows, and a placeholder when nothing is exposed", () => {
    const readOnly = fetchSnippet("u", "k", [table("t", { select: true })]);
    expect(readOnly).toContain("// Read:");
    expect(readOnly).not.toContain("// Create:");
    expect(fetchSnippet("u", "k", [table("t")])).toContain("your_table");
  });

  it("maskKey hides the middle of a key", () => {
    expect(maskKey("vdk_0123456789abcdef_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqr")).toMatch(/^vdk_01234….{4}$/);
    expect(maskKey("short")).toBe("short");
  });
});

describe("dataApi hardening", () => {
  it("parseOrigins rejects malformed hosts and out-of-range ports", () => {
    const r = parseOrigins("https://a..b https://-x.com https://ok.com:99999 https://ok.com:0 https://fine.com:8443");
    expect(r.origins).toEqual(["https://fine.com:8443"]);
    expect(r.invalid.length).toBe(4);
  });

  it("riskNotes warns about spam on insert-only tables", () => {
    expect(riskNotes(table("n", { insert: true })).join(" ")).toMatch(/spam/);
    expect(riskNotes(table("n", { select: true, insert: true }))).toEqual([]);
  });

  it("fetchSnippet tolerates 204 and empty bodies", () => {
    const snip = fetchSnippet("u", "k", [table("t", { select: true })]);
    expect(snip).toContain("204");
    expect(snip).toContain("res.text()");
    expect(snip).not.toContain("return res.json()");
  });
});
