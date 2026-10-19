import { describe, expect, it } from "vitest";
import en from "../../messages/en.json";
import { TABLE_TEMPLATES, shiftDate, templatesAreValid } from "./dataTableTemplates";
import { parseCellInput } from "./dataTables";

const ns = (en as any).dataTables as Record<string, string>;

describe("table templates", () => {
  it("only uses valid column names and distinct ids", () => {
    expect(templatesAreValid()).toBe(true);
    expect(new Set(TABLE_TEMPLATES.map((t) => t.id)).size).toBe(TABLE_TEMPLATES.length);
    for (const t of TABLE_TEMPLATES) {
      const names = t.columns.map((c) => c.name.toLowerCase());
      expect(new Set(names).size).toBe(names.length);
    }
  });

  it("has a translated title and description for each", () => {
    for (const t of TABLE_TEMPLATES) {
      expect(ns[`tpl_${t.id}_title`], t.id).toBeTruthy();
      expect(ns[`tpl_${t.id}_desc`], t.id).toBeTruthy();
    }
  });

  it("makes sample rows that fit their own columns", () => {
    for (const t of TABLE_TEMPLATES) {
      for (const row of t.samples("2026-10-07")) {
        expect(Object.keys(row).length).toBeGreaterThan(0);
        for (const [key, value] of Object.entries(row)) {
          const col = t.columns.find((c) => c.name === key);
          expect(col, `${t.id}.${key}`).toBeTruthy();
          // the same acceptance rule the grid applies to typed text
          expect(parseCellInput(col!.type, col!.type === "json" ? JSON.stringify(value) : String(value)).error, `${t.id}.${key}`).toBeNull();
        }
      }
    }
  });

  it("shifts dates by whole days across month ends", () => {
    expect(shiftDate("2026-10-31", 1)).toBe("2026-11-01");
    expect(shiftDate("2026-03-01", -1)).toBe("2026-02-28");
  });
});
