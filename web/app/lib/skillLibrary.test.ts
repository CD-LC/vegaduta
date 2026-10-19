import { describe, expect, it } from "vitest";
import fs from "node:fs";
import path from "node:path";
import en from "../../messages/en.json";
import { normalizePage, normalizeSkill, normalizeDetail } from "./api/skillLibrary";
import { checkImportFile, diffLines, diffStats, exportFilename, formatBytes, previewMarkdown, MAX_DIFF_LINES } from "./skillLibrary";

describe("diffLines", () => {
  it("reports identical text as all same", () => {
    const rows = diffLines("a\nb", "a\nb");
    expect(rows.every((r) => r.kind === "same")).toBe(true);
  });

  it("pairs an edited line into one change row", () => {
    const rows = diffLines("a\nb\nc", "a\nB\nc");
    expect(rows.map((r) => r.kind)).toEqual(["same", "change", "same"]);
    expect(rows[1]).toMatchObject({ left: "b", right: "B" });
  });

  it("marks pure additions and removals", () => {
    expect(diffStats(diffLines("a", "a\nb\nc"))).toEqual({ added: 2, removed: 0, changed: 0 });
    expect(diffStats(diffLines("a\nb\nc", "a"))).toEqual({ added: 0, removed: 2, changed: 0 });
  });

  it("handles empty sides and CRLF", () => {
    expect(diffLines("", "")).toEqual([]);
    expect(diffStats(diffLines("", "x"))).toEqual({ added: 1, removed: 0, changed: 0 });
    expect(diffStats(diffLines("a\r\nb", "a\nb"))).toEqual({ added: 0, removed: 0, changed: 0 });
  });

  it("degrades instead of freezing on huge input", () => {
    const big = Array.from({ length: MAX_DIFF_LINES + 5 }, (_, i) => `l${i}`).join("\n");
    const rows = diffLines(big, big + "\nextra");
    expect(rows.length).toBeGreaterThan(MAX_DIFF_LINES);
    expect(rows.some((r) => r.kind === "change")).toBe(true);
  });
});

describe("previewMarkdown", () => {
  it("replaces images so the preview never fetches anything", () => {
    const out = previewMarkdown("hi ![logo](https://evil.test/x.png?t=1) and ![](a.png)");
    expect(out).not.toContain("evil.test");
    expect(out).toContain("[image: logo]");
    expect(out).toContain("[image]");
  });
});

describe("checkImportFile", () => {
  it("accepts zip and md, rejects other types, empties and oversize", () => {
    expect(checkImportFile({ name: "a.ZIP", size: 10 })).toEqual({ ok: true });
    expect(checkImportFile({ name: "SKILL.md", size: 10 })).toEqual({ ok: true });
    expect(checkImportFile({ name: "a.exe", size: 10 })).toEqual({ ok: false, reason: "type" });
    expect(checkImportFile({ name: "a.zip", size: 0 })).toEqual({ ok: false, reason: "empty" });
    expect(checkImportFile({ name: "a.zip", size: 11 * 1024 * 1024 })).toEqual({ ok: false, reason: "size" });
  });
});

describe("small formatters", () => {
  it("formats bytes and file names", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2.0 KB");
    expect(exportFilename("my skill/../x")).toBe("my-skill-.-x.zip");
    expect(exportFilename("///")).toBe("skill.zip");
  });
});

describe("response normalizers", () => {
  const row = { id: "s1", name: "N", description: "D", status: "ACTIVE", origin: "LEARNED", version: 3, uses: 7, agentIds: ["a", 5], updatedAt: "2026-01-01" };

  it("keeps a valid row and defends against junk", () => {
    expect(normalizeSkill(row)).toMatchObject({ id: "s1", status: "ACTIVE", origin: "LEARNED", agentIds: ["a"] });
    expect(normalizeSkill({ id: "x", status: "WAT", origin: "??" })).toMatchObject({ status: "PENDING", origin: "AUTHORED", name: "x" });
    expect(normalizeSkill(null)).toBeNull();
    expect(normalizeSkill({ name: "no id" })).toBeNull();
  });

  it("reads bare arrays and Spring pages", () => {
    expect(normalizePage([row]).items).toHaveLength(1);
    expect(normalizePage({ content: [row, {}], totalElements: 9 })).toMatchObject({ total: 9 });
    expect(normalizePage({ items: [row], total: 1 }).items).toHaveLength(1);
    expect(normalizePage(null)).toEqual({ items: [], total: null });
  });

  it("reads detail with files", () => {
    const d = normalizeDetail({ ...row, body: "b", files: [{ path: "a.txt", size: 3 }, { nope: 1 }] });
    expect(d?.files).toEqual([{ path: "a.txt", size: 3 }]);
    expect(d?.body).toBe("b");
  });
});

describe("skillLibrary i18n", () => {
  it("defines every key used by the library components", () => {
    const dir = path.resolve(process.cwd(), "app/components/skillLibrary");
    const cat = (en as unknown as Record<string, Record<string, string>>).skillLibrary;
    const files = fs.readdirSync(dir).filter((f) => f.endsWith(".tsx") && !f.includes(".test."));
    files.push("../../skills/library/page.tsx");
    for (const f of files) {
      const src = fs.readFileSync(path.join(dir, f), "utf8");
      for (const m of src.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) expect(cat[m[1]], `${f}: ${m[1]}`).toBeTypeOf("string");
    }
    for (const s of ["PENDING", "ACTIVE", "REJECTED", "ARCHIVED"]) expect(cat[`status_${s}`]).toBeTypeOf("string");
    for (const o of ["IMPORTED", "LEARNED", "AUTHORED"]) expect(cat[`origin_${o}`]).toBeTypeOf("string");
  });
});
