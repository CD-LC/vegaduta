import { describe, expect, it } from "vitest";
import {
  buildLintRequest,
  collectExpressionItems,
  itemKey,
  mergeExpressionLint,
  quickDiagnostics,
  readFieldStrings,
} from "./expressionLint";
import { EXPRESSION_FIELDS, conditionMode, expressionFieldsOf, isBareExpressionField } from "./nodeDefs";
import { parseLintResponse } from "./lintMerge";

const node = (id: string, type: string, config: Record<string, unknown>, label = id) => ({
  id,
  type,
  data: { label, config },
});

describe("readFieldStrings", () => {
  it("reads simple and dotted paths", () => {
    expect(readFieldStrings({ url: "u" }, "url")).toEqual([{ field: "url", value: "u" }]);
    expect(readFieldStrings({ inputMapping: { query: "q" } }, "inputMapping.query")).toEqual([
      { field: "inputMapping.query", value: "q" },
    ]);
  });

  it("expands arrays with concrete indices", () => {
    const cfg = { headers: [{ name: "a", value: "{{x}}" }, { name: "b", value: "plain" }] };
    expect(readFieldStrings(cfg, "headers[].value")).toEqual([
      { field: "headers[0].value", value: "{{x}}" },
      { field: "headers[1].value", value: "plain" },
    ]);
  });

  it("ignores missing keys, non-strings and wrong shapes", () => {
    expect(readFieldStrings({}, "url")).toEqual([]);
    expect(readFieldStrings({ url: 5 }, "url")).toEqual([]);
    expect(readFieldStrings({ headers: "nope" }, "headers[].value")).toEqual([]);
    expect(readFieldStrings(null, "url")).toEqual([]);
    expect(readFieldStrings({ inputMapping: null }, "inputMapping.query")).toEqual([]);
    expect(readFieldStrings({ headers: [null, 3, { value: 1 }] }, "headers[].value")).toEqual([]);
  });
});

describe("collectExpressionItems", () => {
  const nodes = [
    node("h", "http", {
      url: "https://x/{{ $node[\"A\"].id }}",
      body: "static",
      headers: [{ name: "X", value: "{{ $json.t }}" }],
      query: [],
    }, "Call"),
    node("c", "condition", { mode: "expression", expression: "$json.total > 5" }),
    node("p", "parallel", {}),
    node("l", "loop", { itemsField: "{{ x }}" }),
  ];

  it("collects only fields with expressions, plus bare fields", () => {
    const items = collectExpressionItems(nodes);
    expect(items.map((i) => `${i.nodeId}:${i.field}:${i.bare}`)).toEqual(["h:url:false", "h:headers[0].value:false", "c:expression:true"]);
    expect(items[0].nodeLabel).toBe("Call");
  });

  it("skips unknown node types and empty values", () => {
    expect(collectExpressionItems([node("c", "condition", { expression: "  " })])).toEqual([]);
    expect(collectExpressionItems([{ id: "z", type: "mystery", data: { config: { url: "{{x}}" } } }])).toEqual([]);
    expect(collectExpressionItems([{ id: "z" }])).toEqual([]);
  });

  it("caps the item count", () => {
    const many = Array.from({ length: 10 }, (_, i) => node(`n${i}`, "code", { template: "{{input}}" }));
    expect(collectExpressionItems(many, 4)).toHaveLength(4);
  });
});

describe("nodeDefs expression fields", () => {
  it("exposes the per-type lists and the bare marker", () => {
    expect(expressionFieldsOf("http")).toEqual(["url", "body", "headers[].value", "query[].value"]);
    expect(expressionFieldsOf("parallel")).toEqual([]);
    expect(expressionFieldsOf(undefined)).toEqual([]);
    expect(isBareExpressionField("condition", "expression")).toBe(true);
    expect(isBareExpressionField("http", "url")).toBe(false);
  });

  it("every listed type is a real node type and has no duplicate specs", () => {
    for (const [type, specs] of Object.entries(EXPRESSION_FIELDS)) {
      expect(type).toMatch(/^[a-z_]+$/);
      expect(new Set(specs).size).toBe(specs.length);
    }
  });

  it("condition mode defaults to contains", () => {
    expect(conditionMode(undefined)).toBe("contains");
    expect(conditionMode({ mode: "expression" })).toBe("expression");
    expect(conditionMode({ mode: "weird" })).toBe("contains");
  });
});

describe("quickDiagnostics", () => {
  it("reports unclosed braces in stored coordinates", () => {
    const items = collectExpressionItems([node("c", "code", { template: "hi {{ a" })]);
    const d = quickDiagnostics(items);
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ code: "UNCLOSED_BRACES", start: 3, end: 5, nodeId: "c", field: "template", source: "client" });
  });

  it("checks a bare expression as if wrapped, mapping positions back", () => {
    const items = collectExpressionItems([node("c", "condition", { expression: "a # b" })]);
    const d = quickDiagnostics(items);
    expect(d).toHaveLength(1);
    expect(d[0]).toMatchObject({ code: "UNEXPECTED_CHARACTER", start: 2, end: 3 });
  });

  it("does not blame the synthetic wrapper for an unterminated string in a bare field", () => {
    const items = collectExpressionItems([node("c", "condition", { expression: 'a == "x' })]);
    expect(quickDiagnostics(items).map((d) => d.code)).toEqual(["UNTERMINATED_STRING"]);
  });

  it("is quiet for valid templates", () => {
    const items = collectExpressionItems([node("c", "code", { template: "{{ $json.a + 1 }}" }), node("d", "condition", { expression: "a > 1 && b" })]);
    expect(quickDiagnostics(items)).toEqual([]);
  });
});

describe("bare condition expressions and the server lint", () => {
  const bareItems = collectExpressionItems([node("k", "condition", { mode: "expression", expression: "$json.total > 1000" }, "Check")]);

  it("sends a bare expression wrapped in braces but keys staleness on the stored text", () => {
    const req = buildLintRequest(bareItems);
    expect(req.body).toEqual([{ nodeId: "k", field: "expression", template: "{{ $json.total > 1000 }}" }]);
    expect(req.sent[itemKey("k", "expression")]).toBe("$json.total > 1000");
  });

  it("keeps a server diagnostic for a bare expression (it is no longer dropped as stale)", () => {
    const { sent } = buildLintRequest(bareItems);
    const out = mergeExpressionLint(bareItems, {
      sent,
      diagnostics: [{ nodeId: "k", field: "expression", severity: "error", code: "NOT_UPSTREAM", message: "x", start: 3, end: 8 }],
    });
    expect(out).toHaveLength(1);
    expect(out[0].code).toBe("expression-not-upstream");
  });
});

describe("mergeExpressionLint", () => {
  const items = collectExpressionItems([node("c", "code", { template: "{{ nope( }}" }, "Code")]);
  const { sent } = buildLintRequest(items);

  it("builds a request snapshot keyed by node and field", () => {
    const req = buildLintRequest(items);
    expect(req.body).toEqual([{ nodeId: "c", field: "template", template: "{{ nope( }}" }]);
    expect(req.sent[itemKey("c", "template")]).toBe("{{ nope( }}");
  });

  it("turns client quick checks into open-config issues with the field", () => {
    const out = mergeExpressionLint(items, null);
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({
      code: "expression-unbalanced-bracket",
      severity: "error",
      nodeId: "c",
      source: "client",
      fix: { kind: "open-config", nodeId: "c", field: "template" },
    });
    expect(out[0].message).toContain('"template"');
  });

  it("merges server diagnostics and drops the client's syntax errors for the same item", () => {
    const out = mergeExpressionLint(items, {
      sent,
      diagnostics: [{ nodeId: "c", field: "template", severity: "error", code: "PARSE_ERROR", message: "Unexpected end", start: 3, end: 8 }],
    });
    expect(out.map((i) => i.code)).toEqual(["expression-parse-error"]);
    expect(out[0].source).toBe("server");
  });

  it("keeps client checks when the server only warned", () => {
    const out = mergeExpressionLint(items, {
      sent,
      diagnostics: [{ nodeId: "c", field: "template", severity: "warning", code: "UNKNOWN_FUNCTION", message: "Unknown function nope", start: 3, end: 7 }],
    });
    expect(out.map((i) => i.code).sort()).toEqual(["expression-unbalanced-bracket", "expression-unknown-function"]);
  });

  it("discards server diagnostics for text that has changed since they were requested", () => {
    const edited = collectExpressionItems([node("c", "code", { template: "{{ ok }}" })]);
    const out = mergeExpressionLint(edited, {
      sent,
      diagnostics: [{ nodeId: "c", field: "template", severity: "error", code: "PARSE_ERROR", message: "Unexpected end", start: 3, end: 8 }],
    });
    expect(out).toEqual([]);
  });

  it("discards diagnostics for fields that no longer exist", () => {
    const out = mergeExpressionLint([], {
      sent,
      diagnostics: [{ nodeId: "gone", field: "x", severity: "error", code: "NOT_UPSTREAM", message: "m", start: 0, end: 1 }],
    });
    expect(out).toEqual([]);
  });

  it("sorts errors before warnings and gives distinct stable ids", () => {
    const out = mergeExpressionLint(items, {
      sent,
      diagnostics: [
        { nodeId: "c", field: "template", severity: "warning", code: "LEGACY_UNKNOWN_NAME", message: "w", start: 3, end: 4 },
        { nodeId: "c", field: "template", severity: "warning", code: "STALE_PIN", message: "s", start: 0, end: 0 },
      ],
    });
    expect(out[0].severity).toBe("error");
    expect(new Set(out.map((i) => i.id)).size).toBe(out.length);
    const again = mergeExpressionLint(items, null);
    expect(again[0].id).toBe(out.find((i) => i.code === "expression-unbalanced-bracket")!.id);
  });

  it("collapses exact duplicates", () => {
    const d = { nodeId: "c", field: "template", severity: "warning" as const, code: "STALE_PIN", message: "s", start: 0, end: 1 };
    expect(mergeExpressionLint(items, { sent, diagnostics: [d, d] }).filter((i) => i.code === "expression-stale-pin")).toHaveLength(1);
  });
});

describe("open-config fix parsing keeps the optional field", () => {
  it("round-trips through parseLintResponse", () => {
    const parsed = parseLintResponse({
      issues: [
        { id: "a", code: "x", severity: "warning", message: "m", nodeId: "n", fix: { kind: "open-config", nodeId: "n", field: "url" } },
        { id: "b", code: "x", severity: "warning", message: "m", nodeId: "n", fix: { kind: "open-config", nodeId: "n" } },
      ],
    })!;
    expect(parsed.issues[0].fix).toEqual({ kind: "open-config", nodeId: "n", field: "url" });
    expect(parsed.issues[1].fix).toEqual({ kind: "open-config", nodeId: "n" });
  });
});
