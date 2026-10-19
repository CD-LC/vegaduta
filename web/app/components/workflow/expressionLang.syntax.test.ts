import { describe, expect, it } from "vitest";
import {
  findSegmentAt,
  findSegments,
  hasExpression,
  highlightExpression,
  highlightTemplate,
  overlayMarkers,
  quickCheckTemplate,
  quoteString,
  referencesData,
  tokenize,
  unquoteString,
} from "./expressionLang";

const seg = (s: string) => findSegments(s).map((x) => s.slice(x.start, x.end));

describe("findSegments", () => {
  it("returns nothing for plain text", () => {
    expect(findSegments("hello world")).toEqual([]);
    expect(findSegments("")).toEqual([]);
  });

  it("finds a simple segment with exact offsets", () => {
    const [s] = findSegments("a {{ b }} c");
    expect(s).toEqual({ start: 2, end: 9, innerStart: 4, innerEnd: 7, closed: true });
  });

  it("finds several segments", () => {
    expect(seg("{{a}} and {{b}}")).toEqual(["{{a}}", "{{b}}"]);
  });

  it("does not end a segment at braces inside a double-quoted string", () => {
    expect(seg('{{ "}}" + x }} tail')).toEqual(['{{ "}}" + x }}']);
  });

  it("does not end a segment at braces inside a single-quoted string", () => {
    expect(seg("{{ '}}' }}!")).toEqual(["{{ '}}' }}"]);
  });

  it("honours escaped quotes inside strings", () => {
    expect(seg('{{ "a\\"}}b" }}z')).toEqual(['{{ "a\\"}}b" }}']);
  });

  it("nests object literals", () => {
    expect(seg("{{ {a: {b: 1}} }}")).toEqual(["{{ {a: {b: 1}} }}"]);
    expect(seg("{{ {a: 1}}}")).toEqual(["{{ {a: 1}}}"]);
  });

  it("leaves a stray }} in surrounding text alone", () => {
    expect(seg('{"a": {"b": {{input}}}}')).toEqual(["{{input}}"]);
    expect(findSegments("just }} text")).toEqual([]);
  });

  it("marks an unclosed segment and runs it to the end", () => {
    const [s] = findSegments("x {{ a + ");
    expect(s.closed).toBe(false);
    expect(s.end).toBe(9);
    expect(s.innerEnd).toBe(9);
  });

  it("treats an unterminated string as swallowing the rest", () => {
    const [s] = findSegments('{{ "abc }} more');
    expect(s.closed).toBe(false);
  });

  it("finds a triple-brace as a segment starting with an object literal", () => {
    expect(seg("{{{a:1}}}")).toEqual(["{{{a:1}}}"]);
  });

  it("hasExpression is true only when a segment exists", () => {
    expect(hasExpression("{{input}}")).toBe(true);
    expect(hasExpression("no braces")).toBe(false);
    expect(hasExpression("{ single }")).toBe(false);
    expect(hasExpression(undefined)).toBe(false);
    expect(hasExpression("")).toBe(false);
  });

  it("findSegmentAt includes both inner edges but not the braces", () => {
    const t = "ab {{ x }} cd";
    expect(findSegmentAt(t, 5)?.closed).toBe(true); // right after {{
    expect(findSegmentAt(t, 8)?.closed).toBe(true); // right before }}
    expect(findSegmentAt(t, 4)).toBeNull(); // between the two opening braces
    expect(findSegmentAt(t, 9)).toBeNull(); // between the two closing braces
    expect(findSegmentAt(t, 1)).toBeNull();
  });

  it("findSegmentAt works in an unclosed segment up to the end", () => {
    expect(findSegmentAt("{{ a", 4)).not.toBeNull();
  });
});

describe("string quoting", () => {
  it("round-trips awkward labels", () => {
    for (const label of ['Say "hi"', "it's", "back\\slash", "line\nbreak", "tab\t", "plain", ""]) {
      expect(unquoteString(quoteString(label))).toBe(label);
      expect(unquoteString(quoteString(label, "'"))).toBe(label);
    }
  });

  it("escapes the active quote only", () => {
    expect(quoteString('a"b')).toBe('"a\\"b"');
    expect(quoteString("a'b")).toBe('"a\'b"');
    expect(quoteString("a'b", "'")).toBe("'a\\'b'");
  });

  it("unquote handles unicode escapes and unknown escapes", () => {
    expect(unquoteString('"\\u0041"')).toBe("A");
    expect(unquoteString('"\\q"')).toBe("q");
  });
});

describe("tokenize", () => {
  const kinds = (s: string) => tokenize(s).map((t) => `${t.kind}:${t.text}`);

  it("classifies roots, idents, functions, keywords, numbers, strings and operators", () => {
    expect(kinds('$json.items[0].price * 1.5 > 10 && name == "x"')).toEqual([
      "root:$json",
      "punct:.",
      "ident:items",
      "punct:[",
      "number:0",
      "punct:]",
      "punct:.",
      "ident:price",
      "operator:*",
      "number:1.5",
      "operator:>",
      "number:10",
      "operator:&&",
      "ident:name",
      "operator:==",
      'string:"x"',
    ]);
  });

  it("marks calls as functions, including method form and spaced parens", () => {
    expect(kinds("upper(name)")[0]).toBe("function:upper");
    expect(kinds("items.filter(i => i.qty)")).toContain("function:filter");
    expect(kinds("trim (x)")[0]).toBe("function:trim");
  });

  it("marks true/false/null as keywords", () => {
    expect(kinds("true false null")).toEqual(["keyword:true", "keyword:false", "keyword:null"]);
  });

  it("prefers the longest operator", () => {
    expect(kinds("a ?? b")).toContain("operator:??");
    expect(kinds("a?.b")).toContain("operator:?.");
    expect(kinds("a => a")).toContain("operator:=>");
    expect(kinds("a <= b")).toContain("operator:<=");
    expect(kinds("a != b")).toContain("operator:!=");
  });

  it("flags an unterminated string as an error token", () => {
    const [t] = tokenize('"abc');
    expect(t.kind).toBe("error");
    expect(t.unterminated).toBe(true);
    expect(t.end).toBe(4);
  });

  it("flags stray characters", () => {
    expect(kinds("a @ b")).toContain("error:@");
  });

  it("shifts positions by the offset", () => {
    const [t] = tokenize("abc", 10);
    expect([t.start, t.end]).toEqual([10, 13]);
  });

  it("handles unicode identifiers", () => {
    expect(kinds("名前")[0]).toBe("ident:名前");
  });

  it("never throws on junk", () => {
    for (const s of ["", "\\", "'", '"\\', "{{", "}}", "\u0000", "1e", "1.", ".5", "$", "$$"]) {
      expect(() => tokenize(s)).not.toThrow();
    }
  });
});

describe("highlightTemplate", () => {
  it("covers the whole string contiguously", () => {
    for (const t of ["", "plain", "a {{ b + 1 }} c", "{{ unclosed", "{{}}", '{{ "s" }}{{ x }}']) {
      const spans = highlightTemplate(t);
      let at = 0;
      for (const s of spans) {
        expect(s.start).toBe(at);
        expect(s.end).toBeGreaterThan(s.start);
        at = s.end;
      }
      expect(at).toBe(t.length);
    }
  });

  it("marks text, braces and tokens", () => {
    const spans = highlightTemplate("hi {{ $json.a }}");
    expect(spans[0]).toEqual({ kind: "text", start: 0, end: 3 });
    expect(spans[1]).toEqual({ kind: "brace", start: 3, end: 5 });
    expect(spans.find((s) => s.kind === "root")).toEqual({ kind: "root", start: 6, end: 11 });
    expect(spans[spans.length - 1]).toEqual({ kind: "brace", start: 14, end: 16 });
  });
});

describe("quickCheckTemplate", () => {
  const codes = (t: string) => quickCheckTemplate(t).map((d) => d.code);

  it("accepts valid templates", () => {
    expect(codes("Hello {{ $json.name }}, total {{ price * 2 }}")).toEqual([]);
    expect(codes("no expressions")).toEqual([]);
    expect(codes('{"a": {{input}}}')).toEqual([]);
    expect(codes('{{ {a: [1, 2, {b: "}"}]} }}')).toEqual([]);
  });

  it("flags unclosed braces at the opening", () => {
    const d = quickCheckTemplate("x {{ a");
    expect(d.map((x) => x.code)).toContain("UNCLOSED_BRACES");
    expect(d.find((x) => x.code === "UNCLOSED_BRACES")).toMatchObject({ severity: "error", start: 2, end: 4 });
  });

  it("warns on empty expressions", () => {
    const d = quickCheckTemplate("a {{  }} b");
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ code: "EMPTY_EXPRESSION", severity: "warning", start: 2, end: 8 });
  });

  it("flags an unterminated string", () => {
    expect(codes('{{ "abc }}')).toContain("UNTERMINATED_STRING");
  });

  it("flags unexpected characters", () => {
    const d = quickCheckTemplate("{{ a # b }}");
    expect(d[0]).toMatchObject({ code: "UNEXPECTED_CHARACTER", start: 5, end: 6 });
  });

  it("flags unbalanced brackets in either direction", () => {
    expect(codes("{{ upper(a }}")).toContain("UNBALANCED_BRACKET");
    expect(codes("{{ a) }}")).toContain("UNBALANCED_BRACKET");
    expect(codes("{{ a[0) }}")).toContain("UNBALANCED_BRACKET");
    expect(codes("{{ f(a, [1, 2]) }}")).toEqual([]);
  });

  it("reports positions in template coordinates and sorted", () => {
    const d = quickCheckTemplate("{{ a # }} {{ b @ }}");
    expect(d.map((x) => x.start)).toEqual([5, 15]);
  });

  it("does not report anything for empty input", () => {
    expect(quickCheckTemplate("")).toEqual([]);
  });
});

describe("referencesData", () => {
  it("is true for roots and bare names", () => {
    expect(referencesData("{{ $json.a }}")).toBe(true);
    expect(referencesData("{{ name }}")).toBe(true);
    expect(referencesData("{{input}}")).toBe(true);
  });
  it("is false for pure literals and text", () => {
    expect(referencesData("{{ 1 + 2 }}")).toBe(false);
    expect(referencesData('{{ upper("x") }}')).toBe(false);
    expect(referencesData("plain")).toBe(false);
    expect(referencesData("{{ $now }}")).toBe(true);
  });
});

describe("highlightExpression", () => {
  it("covers a bare expression contiguously with token kinds", () => {
    const src = "$json.total > 10";
    const spans = highlightExpression(src);
    let at = 0;
    for (const s of spans) {
      expect(s.start).toBe(at);
      at = s.end;
    }
    expect(at).toBe(src.length);
    expect(spans[0]).toEqual({ kind: "root", start: 0, end: 5 });
    expect(highlightExpression("")).toEqual([]);
  });
});

describe("overlayMarkers", () => {
  const text = "{{ a + b }}";
  const spans = highlightTemplate(text);

  it("returns the spans unchanged without markers", () => {
    expect(overlayMarkers(spans, [], text.length)).toBe(spans);
  });

  it("splits spans at marker edges and tags only the covered part", () => {
    const out = overlayMarkers(spans, [{ start: 3, end: 4, severity: "error" }], text.length);
    const tagged = out.filter((s) => s.marker);
    expect(tagged).toHaveLength(1);
    expect([tagged[0].start, tagged[0].end, tagged[0].marker]).toEqual([3, 4, "error"]);
    let at = 0;
    for (const s of out) {
      expect(s.start).toBe(at);
      at = s.end;
    }
    expect(at).toBe(text.length);
  });

  it("splits a token that a marker only partly covers", () => {
    const out = overlayMarkers([{ kind: "ident", start: 0, end: 6 }], [{ start: 2, end: 4, severity: "warning" }], 6);
    expect(out.map((s) => [s.start, s.end, s.marker])).toEqual([[0, 2, undefined], [2, 4, "warning"], [4, 6, undefined]]);
  });

  it("widens empty markers and clamps out-of-range ones", () => {
    const a = overlayMarkers([{ kind: "plain", start: 0, end: 4 }], [{ start: 1, end: 1, severity: "error" }], 4);
    expect(a.find((s) => s.marker)).toMatchObject({ start: 1, end: 2 });
    const b = overlayMarkers([{ kind: "plain", start: 0, end: 4 }], [{ start: -5, end: 99, severity: "error" }], 4);
    expect(b.every((s) => s.marker === "error")).toBe(true);
    expect(overlayMarkers([{ kind: "plain", start: 0, end: 4 }], [{ start: 9, end: 12, severity: "error" }], 4)).toEqual([
      { kind: "plain", start: 0, end: 4 },
    ]);
  });

  it("lets an error win over an overlapping warning", () => {
    const out = overlayMarkers(
      [{ kind: "plain", start: 0, end: 4 }],
      [{ start: 0, end: 4, severity: "warning" }, { start: 1, end: 3, severity: "error" }],
      4,
    );
    expect(out.find((s) => s.start === 1)?.marker).toBe("error");
    expect(out.find((s) => s.start === 0)?.marker).toBe("warning");
  });
});
