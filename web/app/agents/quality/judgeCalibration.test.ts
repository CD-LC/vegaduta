import { describe, expect, it } from "vitest";
import { formatKappa, formatRatio, parseCalibrationRows, splitRecords } from "./judgeCalibration";

describe("splitRecords", () => {
  it("splits comma-separated rows and skips blank lines", () => {
    const out = splitRecords("a,b,c\n\nd,e,f\n");
    expect(out.map((r) => r.fields)).toEqual([
      ["a", "b", "c"],
      ["d", "e", "f"],
    ]);
    expect(out.map((r) => r.line)).toEqual([1, 3]);
  });

  it("prefers tabs when the first line has more tabs than commas (spreadsheet paste)", () => {
    const out = splitRecords("hello, world\tgood, answer\tbad\tA");
    expect(out[0].fields).toEqual(["hello, world", "good, answer", "bad", "A"]);
  });

  it("handles quoted fields with delimiters, doubled quotes and embedded newlines", () => {
    const out = splitRecords('"q, with comma","say ""hi""","line1\nline2",B\nnext,a,b,A');
    expect(out[0].fields).toEqual(["q, with comma", 'say "hi"', "line1\nline2", "B"]);
    // the second record starts on line 3 because the quoted field spanned a newline
    expect(out[1].line).toBe(3);
  });

  it("normalises CRLF and CR line endings", () => {
    expect(splitRecords("a,b\r\nc,d\re,f").map((r) => r.fields)).toEqual([
      ["a", "b"],
      ["c", "d"],
      ["e", "f"],
    ]);
  });

  it("returns nothing for empty or whitespace-only input", () => {
    expect(splitRecords("")).toEqual([]);
    expect(splitRecords("  \n \n")).toEqual([]);
  });
});

describe("parseCalibrationRows", () => {
  it("parses rows, upper-cases labels and keeps an optional rationale", () => {
    const { rows, errors } = parseCalibrationRows("q1,good,bad,a,clearer\nq2,x,y,B");
    expect(errors).toEqual([]);
    expect(rows).toEqual([
      { input: "q1", outputA: "good", outputB: "bad", humanLabel: "A", rationale: "clearer" },
      { input: "q2", outputA: "x", outputB: "y", humanLabel: "B" },
    ]);
  });

  it("skips a header row", () => {
    const { rows, errors } = parseCalibrationRows("input,outputA,outputB,humanLabel,rationale\nq,a,b,A");
    expect(errors).toEqual([]);
    expect(rows).toHaveLength(1);
  });

  it("does not treat a data row as a header", () => {
    expect(parseCalibrationRows("question one,a,b,A").rows).toHaveLength(1);
    // a header word only counts on the FIRST line
    const { rows, errors } = parseCalibrationRows("q,a,b,A\ninput,a,b,B");
    expect(errors).toEqual([]);
    expect(rows).toHaveLength(2);
  });

  it("reports bad lines with their line number and keeps the good ones", () => {
    const { rows, errors } = parseCalibrationRows("q1,a,b,A\nq2,a,b\nq3,a,b,C\n,a,b,A\nq5,a,b,B");
    expect(rows.map((r) => r.input)).toEqual(["q1", "q5"]);
    expect(errors).toHaveLength(3);
    expect(errors[0]).toContain("Line 2");
    expect(errors[0]).toContain("3 columns");
    expect(errors[1]).toContain("Line 3");
    expect(errors[1]).toContain('"C"');
    expect(errors[2]).toContain("Line 4");
  });

  it("stitches an unquoted rationale that contained the delimiter", () => {
    const { rows } = parseCalibrationRows("q,a,b,A,short, and clear");
    expect(rows[0].rationale).toBe("short, and clear");
  });

  it("returns empty results for empty text", () => {
    expect(parseCalibrationRows("")).toEqual({ rows: [], errors: [] });
  });
});

describe("formatters", () => {
  it("shows an em dash for an undefined kappa, never zero", () => {
    expect(formatKappa(null)).toBe("—");
    expect(formatKappa(undefined)).toBe("—");
    expect(formatKappa(Number.NaN)).toBe("—");
    expect(formatKappa(0)).toBe("0.00");
    expect(formatKappa(0.7149)).toBe("0.71");
    expect(formatKappa(-0.2)).toBe("-0.20");
  });

  it("formats ratios as whole percentages", () => {
    expect(formatRatio(null)).toBe("—");
    expect(formatRatio(0.875)).toBe("88%");
    expect(formatRatio(0)).toBe("0%");
  });
});
