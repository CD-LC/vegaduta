import { describe, expect, it } from "vitest";
import { buildImportPlan, chunk, importColumnName, inferColumnType, mapRows, parseCsv, toCsv, uniqueColumnNames, unneutralize } from "./dataTableCsv";

describe("parseCsv", () => {
  it("reads quoted fields, doubled quotes and newlines inside quotes", () => {
    const p = parseCsv('a,b\r\n"x, y","say ""hi"""\r\n"multi\nline",z\r\n');
    expect(p.header).toEqual(["a", "b"]);
    expect(p.rows).toEqual([
      ["x, y", 'say "hi"'],
      ["multi\nline", "z"],
    ]);
  });

  it("sniffs semicolons and tabs from the first line and strips a BOM", () => {
    expect(parseCsv("﻿a;b;c\n1;2;3").delimiter).toBe(";");
    expect(parseCsv("a\tb\n1\t2").header).toEqual(["a", "b"]);
    expect(parseCsv("a,b\n1,2").delimiter).toBe(",");
  });

  it("skips blank lines and gives nothing for an empty file", () => {
    expect(parseCsv("a,b\n\n1,2\n\n").rows).toEqual([["1", "2"]]);
    expect(parseCsv("").header).toEqual([]);
  });
});

describe("column names", () => {
  it("sanitises like the server", () => {
    expect(importColumnName("Order total ($)", 1)).toBe("Order_total");
    expect(importColumnName("2nd", 1)).toBe("c_2nd");
    expect(importColumnName("  ", 3)).toBe("column_3");
    expect(importColumnName("x".repeat(80), 1)).toHaveLength(64);
  });

  it("makes names unique case-insensitively", () => {
    expect(uniqueColumnNames(["Name", "name", "NAME", "id"])).toEqual(["Name", "name_2", "NAME_3", "id"]);
  });
});

describe("inferColumnType", () => {
  it("infers number, boolean and date only when every value fits", () => {
    expect(inferColumnType(["1", "2.5", "-3", ""])).toBe("number");
    expect(inferColumnType(["true", "FALSE"])).toBe("boolean");
    expect(inferColumnType(["2026-03-04", "2026-12-31"])).toBe("date");
    expect(inferColumnType(["2026-03-04T10:00:00Z"])).toBe("date");
    expect(inferColumnType(["1", "two"])).toBe("text");
  });

  it("keeps leading zeros, long digit runs, impossible dates and empty columns as text", () => {
    expect(inferColumnType(["007"])).toBe("text");
    expect(inferColumnType(["1234567890123456"])).toBe("text");
    expect(inferColumnType(["2026-02-31"])).toBe("text");
    expect(inferColumnType(["", " "])).toBe("text");
    expect(inferColumnType(["0.5", "0"])).toBe("number");
  });
});

describe("buildImportPlan + mapRows", () => {
  const plan = buildImportPlan("Name,Age,Joined,Active\nAsha,30,2026-01-02,true\nBo,,2026-02-03,false\n");

  it("proposes typed columns with samples", () => {
    expect(plan.columns.map((c) => [c.name, c.type])).toEqual([
      ["Name", "text"],
      ["Age", "number"],
      ["Joined", "date"],
      ["Active", "boolean"],
    ]);
    expect(plan.columns[0].sample).toEqual(["Asha", "Bo"]);
    expect(plan.rows).toHaveLength(2);
  });

  it("types cells and omits empty ones", () => {
    const m = mapRows(plan.columns, plan.rows);
    expect(m.rows).toEqual([
      { Name: "Asha", Age: 30, Joined: "2026-01-02", Active: true },
      { Name: "Bo", Joined: "2026-02-03", Active: false },
    ]);
    expect(m.skipped).toBe(0);
  });

  it("leaves out a row with a cell that does not fit, and says which", () => {
    const cols = [
      { name: "n", type: "number" as const },
      { name: "t", type: "text" as const },
    ];
    const m = mapRows(cols, [["1", "a"], ["oops", "b"], ["", ""], ["3", ""]]);
    expect(m.rows).toEqual([{ n: 1, t: "a" }, { n: 3 }]);
    expect(m.skipped).toBe(2);
    expect(m.problems).toEqual([{ row: 2, column: "n", error: "number" }]);
  });

  it("honours a type the person overrode", () => {
    const m = mapRows([{ name: "zip", type: "text" }], [["007"]]);
    expect(m.rows).toEqual([{ zip: "007" }]);
  });

  it("restores quote-guarded formula text from an export", () => {
    expect(unneutralize("'=SUM(A1)")).toBe("=SUM(A1)");
    expect(unneutralize("''=x")).toBe("'=x");
    expect(unneutralize("'plain")).toBe("'plain");
    expect(mapRows([{ name: "t", type: "text" }], [["'=1+1"]]).rows).toEqual([{ t: "=1+1" }]);
  });
});

describe("toCsv", () => {
  const columns = [
    { name: "title", type: "text" as const },
    { name: "n", type: "number" as const },
    { name: "meta", type: "json" as const },
  ];
  const row = (data: Record<string, unknown>) => ({ data });

  it("writes a header, CRLF lines and quotes what needs it", () => {
    const csv = toCsv(columns, [row({ title: 'a, "b"', n: 2, meta: { k: 1 } }), row({ title: "x" })]);
    expect(csv).toBe('title,n,meta\r\n"a, ""b""",2,"{""k"":1}"\r\nx,,\r\n');
  });

  it("guards formula-looking text but never numbers", () => {
    const csv = toCsv(columns, [row({ title: "=cmd()", n: -5 })]);
    expect(csv.split("\r\n")[1]).toBe("'=cmd(),-5,");
  });

  it("round-trips through the importer", () => {
    const csv = toCsv(columns, [row({ title: "=cmd()", n: -5 }), row({ title: "plain", n: 1.5 })]);
    const plan = buildImportPlan(csv);
    const m = mapRows(columns, plan.rows);
    expect(m.rows).toEqual([{ title: "=cmd()", n: -5 }, { title: "plain", n: 1.5 }]);
  });
});

describe("chunk", () => {
  it("splits into batches", () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
    expect(chunk([], 3)).toEqual([]);
    expect(chunk([1, 2], 0)).toEqual([[1], [2]]);
  });
});
