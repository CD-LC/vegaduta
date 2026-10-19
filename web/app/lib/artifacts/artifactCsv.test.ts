import { describe, it, expect } from "vitest";
import { CSV_MAX_CELL_CHARS, CSV_MAX_COLS, CSV_MAX_ROWS, detectDelimiter, parseCsv } from "./artifactCsv";

describe("parseCsv", () => {
  it("parses a plain table", () => {
    expect(parseCsv("a,b,c\n1,2,3").rows).toEqual([["a", "b", "c"], ["1", "2", "3"]]);
  });

  it("handles quotes, doubled quotes, embedded commas and newlines", () => {
    const t = parseCsv('name,note\n"Smith, J","said ""hi"""\n"multi\nline",x');
    expect(t.rows).toEqual([["name", "note"], ["Smith, J", 'said "hi"'], ["multi\nline", "x"]]);
  });

  it("handles CRLF and CR line endings and skips blank lines", () => {
    expect(parseCsv("a,b\r\n1,2\r\n\r\n3,4\r\n").rows).toEqual([["a", "b"], ["1", "2"], ["3", "4"]]);
    expect(parseCsv("a,b\r1,2").rows).toEqual([["a", "b"], ["1", "2"]]);
  });

  it("detects tab and semicolon delimiters", () => {
    expect(detectDelimiter("a\tb\tc")).toBe("\t");
    expect(detectDelimiter("a;b;c")).toBe(";");
    expect(detectDelimiter("a,b;c")).toBe(",");
    expect(parseCsv("a;b\n1;2").rows).toEqual([["a", "b"], ["1", "2"]]);
  });

  it("returns no rows for empty or non-string input", () => {
    expect(parseCsv("").rows).toEqual([]);
    expect(parseCsv(undefined).rows).toEqual([]);
    expect(parseCsv(42).rows).toEqual([]);
  });

  it("treats formula-looking cells and html as plain text", () => {
    const t = parseCsv('=HYPERLINK("http://evil"),<script>x</script>');
    expect(t.rows[0][1]).toBe("<script>x</script>");
  });

  it("caps rows, columns and cell length and reports truncation", () => {
    const rows = Array.from({ length: CSV_MAX_ROWS + 50 }, (_, i) => "r" + i + ",x").join("\n");
    const r = parseCsv(rows);
    expect(r.rows.length).toBe(CSV_MAX_ROWS);
    expect(r.truncated).toBe(true);

    const wide = parseCsv(Array.from({ length: CSV_MAX_COLS + 10 }, (_, i) => "c" + i).join(","));
    expect(wide.rows[0].length).toBe(CSV_MAX_COLS);
    expect(wide.truncated).toBe(true);

    const long = parseCsv("x".repeat(CSV_MAX_CELL_CHARS + 500) + ",y");
    expect(long.rows[0][0].length).toBe(CSV_MAX_CELL_CHARS);
    expect(long.truncated).toBe(true);
  });

  it("does not hang on an unterminated quote", () => {
    const t = parseCsv('a,"never closed\nmore');
    expect(t.rows.length).toBeGreaterThan(0);
  });
});
