import { describe, expect, it } from "vitest";
import {
  activeFilters,
  buildRowsQuery,
  cellOf,
  cellToDisplay,
  cellToInput,
  csvFilename,
  emptyFilterRow,
  isCellUnchanged,
  isValidColumnName,
  nextSort,
  opsForType,
  opTakesValue,
  pageInfo,
  parseCellInput,
  suggestColumnName,
  usagePercent,
  type DataTableFilterRow,
} from "./dataTables";

describe("column names", () => {
  it("follows the server grammar", () => {
    expect(isValidColumnName("email")).toBe(true);
    expect(isValidColumnName(" order_total2 ")).toBe(true);
    for (const bad of ["", "1abc", "_hidden", "has space", "a'b", "x-y", "a".repeat(65)]) {
      expect(isValidColumnName(bad)).toBe(false);
    }
  });

  it("turns free text into a valid name or nothing", () => {
    expect(suggestColumnName("Order total ($)")).toBe("Order_total");
    expect(suggestColumnName("9 lives")).toBe("c_9_lives");
    expect(suggestColumnName("  ")).toBe("");
    expect(suggestColumnName("???")).toBe("");
    expect(suggestColumnName("x".repeat(100))).toHaveLength(64);
    expect(isValidColumnName(suggestColumnName("Order total ($)"))).toBe(true);
  });
});

describe("parseCellInput", () => {
  it("keeps text exactly as typed, including empty", () => {
    expect(parseCellInput("text", "  hi ")).toEqual({ value: "  hi ", error: null });
    expect(parseCellInput("text", "")).toEqual({ value: "", error: null });
  });

  it("clears a non-text cell on empty input", () => {
    for (const type of ["number", "boolean", "date", "json"] as const) {
      expect(parseCellInput(type, "  ")).toEqual({ value: null, error: null });
    }
  });

  it("parses numbers and rejects junk", () => {
    expect(parseCellInput("number", " 12.5 ")).toEqual({ value: 12.5, error: null });
    expect(parseCellInput("number", "-3")).toEqual({ value: -3, error: null });
    expect(parseCellInput("number", "abc").error).toBe("number");
    expect(parseCellInput("number", "Infinity").error).toBe("number");
  });

  it("parses the boolean spellings the server accepts", () => {
    expect(parseCellInput("boolean", "Yes").value).toBe(true);
    expect(parseCellInput("boolean", "0").value).toBe(false);
    expect(parseCellInput("boolean", "maybe").error).toBe("boolean");
  });

  it("accepts ISO dates and date-times only", () => {
    expect(parseCellInput("date", "2026-03-04")).toEqual({ value: "2026-03-04", error: null });
    expect(parseCellInput("date", "2026-03-04 10:00:00").value).toBe("2026-03-04T10:00:00");
    expect(parseCellInput("date", "04/03/2026").error).toBe("date");
    expect(parseCellInput("date", "2026-13-45").error).toBe("date");
  });

  it("parses json and reports bad json", () => {
    expect(parseCellInput("json", '{"a":[1,2]}').value).toEqual({ a: [1, 2] });
    expect(parseCellInput("json", "plain").error).toBe("json");
  });
});

describe("cell rendering", () => {
  it("round trips stored values into editor text", () => {
    expect(cellToInput("text", null)).toBe("");
    expect(cellToInput("number", 5)).toBe("5");
    expect(cellToInput("boolean", false)).toBe("false");
    expect(cellToInput("json", { a: 1 })).toBe('{"a":1}');
    expect(cellToInput("json", "x")).toBe("x");
  });

  it("cuts long cells for display only", () => {
    const long = "x".repeat(500);
    expect(cellToDisplay("text", long).length).toBe(161);
    expect(cellToDisplay("text", "short")).toBe("short");
  });

  it("detects an edit that changes nothing", () => {
    expect(isCellUnchanged("number", 5, "5")).toBe(true);
    expect(isCellUnchanged("number", 5, "6")).toBe(false);
    expect(isCellUnchanged("number", null, "")).toBe(true);
    expect(isCellUnchanged("text", null, "")).toBe(true);
    expect(isCellUnchanged("text", "a", "a")).toBe(true);
    expect(isCellUnchanged("text", "a", "b")).toBe(false);
    expect(isCellUnchanged("json", { a: 1 }, '{ "a": 1 }')).toBe(true);
    expect(isCellUnchanged("number", 5, "abc")).toBe(false);
  });

  it("reads a missing cell as null", () => {
    expect(cellOf({ id: "1", data: { a: 1 }, createdAt: "", updatedAt: "" }, "b")).toBeNull();
    expect(cellOf({ id: "1", data: { a: 1 }, createdAt: "", updatedAt: "" }, "a")).toBe(1);
  });
});

describe("filters and queries", () => {
  it("only offers operators the server accepts for the type", () => {
    expect(opsForType("boolean")).toEqual(["eq", "ne", "is_null", "not_null"]);
    expect(opsForType("number")).not.toContain("contains");
    expect(opsForType("text")).toContain("starts_with");
    expect(opsForType("json")).not.toContain("in");
  });

  it("knows which operators take a value", () => {
    expect(opTakesValue("eq")).toBe(true);
    expect(opTakesValue("is_null")).toBe(false);
    expect(opTakesValue("not_null")).toBe(false);
  });

  it("keeps only complete conditions", () => {
    const rows: DataTableFilterRow[] = [
      { column: "qty", op: "gt", value: "2" },
      { column: "", op: "eq", value: "x" },
      { column: "name", op: "eq", value: "  " },
      { column: "due", op: "is_null", value: "" },
    ];
    expect(activeFilters(rows).map((r) => r.column)).toEqual(["qty", "due"]);
    expect(activeFilters([emptyFilterRow()])).toEqual([]);
  });

  it("builds the rows query string", () => {
    const qs = new URLSearchParams(
      buildRowsQuery({
        filters: [
          { column: "qty", op: "gte", value: "2" },
          { column: "due", op: "is_null", value: "ignored" },
        ],
        sort: { column: "qty", direction: "desc" },
        limit: 50,
        offset: 100,
      })
    );
    expect(JSON.parse(qs.get("filter")!)).toEqual([{ column: "qty", op: "gte", value: "2" }, { column: "due", op: "is_null" }]);
    expect(qs.get("sort")).toBe("qty:desc");
    expect(qs.get("limit")).toBe("50");
    expect(qs.get("offset")).toBe("100");
  });

  it("omits empty parts of the query", () => {
    expect(buildRowsQuery({})).toBe("");
    expect(buildRowsQuery({ filters: [emptyFilterRow()], limit: 25, offset: 0 })).toBe("limit=25");
  });

  it("cycles the sort on repeated header clicks", () => {
    const asc = nextSort(null, "qty");
    expect(asc).toEqual({ column: "qty", direction: "asc" });
    const desc = nextSort(asc, "qty");
    expect(desc).toEqual({ column: "qty", direction: "desc" });
    expect(nextSort(desc, "qty")).toBeNull();
    expect(nextSort(desc, "name")).toEqual({ column: "name", direction: "asc" });
  });
});

describe("paging and files", () => {
  it("describes the visible window", () => {
    expect(pageInfo(0, 50, 120, 50)).toEqual({ from: 1, to: 50, hasPrev: false, hasNext: true });
    expect(pageInfo(100, 50, 120, 20)).toEqual({ from: 101, to: 120, hasPrev: true, hasNext: false });
    expect(pageInfo(0, 50, 0, 0)).toEqual({ from: 0, to: 0, hasPrev: false, hasNext: false });
    expect(pageInfo(50, 50, 40, 0)).toEqual({ from: 0, to: 0, hasPrev: true, hasNext: false });
  });

  it("makes safe csv file names", () => {
    expect(csvFilename("Orders 2026")).toBe("Orders-2026.csv");
    expect(csvFilename("../../etc/passwd")).toBe("etc-passwd.csv");
    expect(csvFilename("   ")).toBe("data-table.csv");
  });

  it("computes row-limit usage", () => {
    expect(usagePercent(50, 200)).toBe(25);
    expect(usagePercent(500, 200)).toBe(100);
    expect(usagePercent(1, 0)).toBe(0);
  });
});
