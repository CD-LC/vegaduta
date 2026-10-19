import { describe, expect, it } from "vitest";
import {
  applyCompletion,
  buildCompletions,
  completionContextAt,
  parseChain,
  rankCompletions,
  scoreMatch,
  type CompletionContext,
  type CompletionSources,
} from "./expressionLang";
import type { ExpressionFunctionDoc, UpstreamSchemaNode } from "../../lib/api/workflowExpressions";

describe("completionContextAt", () => {
  const at = (t: string, marker = "|") => {
    const cursor = t.indexOf(marker);
    const text = t.replace(marker, "");
    return { text, ctx: completionContextAt(text, cursor), cursor };
  };

  it("is null outside {{ }}", () => {
    expect(at("hel|lo").ctx).toBeNull();
    expect(at("{{ a }} x|").ctx).toBeNull();
  });

  it("is null between the braces of the opener", () => {
    expect(at("{|{ a }}").ctx).toBeNull();
  });

  it("offers ident completion at an empty spot inside a segment", () => {
    const { ctx } = at("{{ | }}");
    expect(ctx).toMatchObject({ kind: "ident", prefix: "" });
  });

  it("captures a partial identifier and its replace range", () => {
    const { ctx, text } = at("say {{ upp| }}");
    expect(ctx).toMatchObject({ kind: "ident", prefix: "upp" });
    expect(text.slice((ctx as CompletionContext & { replaceStart: number }).replaceStart, (ctx as CompletionContext & { replaceEnd: number }).replaceEnd)).toBe("upp");
  });

  it("captures $-prefixed roots", () => {
    expect(at("{{ $js| }}").ctx).toMatchObject({ kind: "ident", prefix: "$js" });
    expect(at("{{ $| }}").ctx).toMatchObject({ kind: "ident", prefix: "$" });
  });

  it("extends the replace range over the rest of the word", () => {
    const { ctx, text } = at("{{ up|per }}");
    expect(ctx).toMatchObject({ kind: "ident", prefix: "up" });
    if (ctx?.kind === "ident") expect(text.slice(ctx.replaceStart, ctx.replaceEnd)).toBe("upper");
  });

  it("does not complete straight after a finished term unless forced", () => {
    expect(at("{{ a | }}").ctx).toBeNull();
    expect(at("{{ f(x)| }}").ctx).toBeNull();
    expect(at('{{ "s" | }}').ctx).toBeNull();
    const t = "{{ a  }}";
    expect(completionContextAt(t, 5, true)).toMatchObject({ kind: "ident", prefix: "" });
  });

  it("completes after an operator or opening bracket", () => {
    expect(at("{{ a + | }}").ctx).toMatchObject({ kind: "ident" });
    expect(at("{{ f(| }}").ctx).toMatchObject({ kind: "ident" });
    expect(at("{{ a, | }}").ctx).toMatchObject({ kind: "ident" });
  });

  it("is null inside an ordinary string literal", () => {
    expect(at('{{ "hel|lo" }}').ctx).toBeNull();
    expect(at("{{ 'x|' }}").ctx).toBeNull();
  });

  it("is null in the middle of a number", () => {
    expect(at("{{ 12| }}").ctx).toBeNull();
  });

  it("detects a member context with the chain before the dot", () => {
    const { ctx } = at('{{ $node["A"].ite| }}');
    expect(ctx).toMatchObject({ kind: "member", prefix: "ite", chain: '$node["A"]' });
  });

  it("detects a member context right after the dot", () => {
    expect(at("{{ $json.| }}").ctx).toMatchObject({ kind: "member", prefix: "", chain: "$json" });
  });

  it("captures deep chains with indices", () => {
    expect(at('{{ $node["A"].items[0].| }}').ctx).toMatchObject({ kind: "member", chain: '$node["A"].items[0]' });
  });

  it("handles ?. and whitespace before the dot target", () => {
    expect(at("{{ $json?.| }}").ctx).toMatchObject({ kind: "member", chain: "$json" });
  });

  it("captures chains after operators", () => {
    expect(at("{{ 1 + $json.a.| }}").ctx).toMatchObject({ kind: "member", chain: "$json.a" });
  });

  it("captures call chains", () => {
    expect(at('{{ $node.id("n7").| }}').ctx).toMatchObject({ kind: "member", chain: '$node.id("n7")' });
  });

  it("gives a null chain after a call or literal", () => {
    expect(at("{{ items.filter(x => x).| }}").ctx).toMatchObject({ kind: "member", chain: "items.filter(x => x)" });
    expect(at("{{ [1,2].| }}").ctx).toMatchObject({ kind: "member", chain: null });
  });

  it("detects the node label inside $node[\"", () => {
    const { ctx, text } = at('{{ $node["Fe| }}');
    expect(ctx).toMatchObject({ kind: "node-label", prefix: "Fe", quote: '"', closed: false });
    if (ctx?.kind === "node-label") expect(text.slice(ctx.replaceStart, ctx.replaceEnd)).toBe("Fe");
  });

  it("detects a label being edited in place and marks it closed", () => {
    const { ctx, text } = at('{{ $node["Fe|tch"].x }}');
    expect(ctx).toMatchObject({ kind: "node-label", prefix: "Fe", closed: true });
    if (ctx?.kind === "node-label") expect(text.slice(ctx.replaceStart, ctx.replaceEnd)).toBe("Fetch");
  });

  it("supports single quotes and spaces before the bracket", () => {
    expect(at("{{ $node [ 'A| }}").ctx).toMatchObject({ kind: "node-label", quote: "'" });
  });

  it("does not treat other bracketed strings as node labels", () => {
    expect(at('{{ $json["A|"] }}').ctx).toBeNull();
  });

  it("handles a segment without a closing brace", () => {
    expect(at("{{ $jso|").ctx).toMatchObject({ kind: "ident", prefix: "$jso" });
  });

  it("never throws on random junk at every caret position", () => {
    const junk = ['{{ $node["a\\', "{{ [[[(((", '{{ "', "{{ ]]]", "{{ a.b.c.[", "{{ . }}", "{{ ?. }}", "{{ ).x. }}"];
    for (const t of junk) for (let i = 0; i <= t.length; i++) expect(() => completionContextAt(t, i, true)).not.toThrow();
  });
});

describe("scoring and ranking", () => {
  it("orders exact prefix > case-insensitive prefix > word boundary > substring > subsequence", () => {
    const s = (l: string) => scoreMatch("pr", l);
    expect(s("price")).toBeGreaterThan(s("Price"));
    expect(s("Price")).toBeGreaterThan(s("unitPrice"));
    expect(s("unitPrice")).toBeGreaterThan(s("express"));
    expect(s("express")).toBeGreaterThan(s("pXr"));
    expect(scoreMatch("pr", "ppx")).toBe(0);
    expect(scoreMatch("prc", "price")).toBeGreaterThan(0);
  });

  it("matches word boundaries with separators and camelCase", () => {
    expect(scoreMatch("pr", "unit_price")).toBe(60);
    expect(scoreMatch("pr", "unitPrice")).toBe(60);
    expect(scoreMatch("js", "$json")).toBeGreaterThan(0);
  });

  it("empty prefix matches everything equally", () => {
    expect(scoreMatch("", "anything")).toBe(1);
  });

  it("prefers shorter labels among equal prefix matches", () => {
    expect(scoreMatch("to", "top")).toBeGreaterThan(scoreMatch("to", "toNumber"));
  });

  it("rankCompletions filters non-matches, sorts, and caps", () => {
    const mk = (label: string, kind: "field" | "function" | "root" = "field") => ({ label, insertText: label, kind, score: 0 });
    const out = rankCompletions([mk("zzz"), mk("total"), mk("toNumber", "function"), mk("Total"), mk("stock")], "to");
    expect(out.map((x) => x.label)).toEqual(["total", "toNumber", "Total", "stock"]);
    expect(rankCompletions([mk("a1"), mk("a2"), mk("a3")], "a", 2)).toHaveLength(2);
  });

  it("breaks ties by kind then alphabetically", () => {
    const mk = (label: string, kind: "field" | "function" | "root") => ({ label, insertText: label, kind, score: 0 });
    const out = rankCompletions([mk("bb", "function"), mk("ba", "field"), mk("bc", "root")], "b");
    expect(out.map((x) => x.label)).toEqual(["ba", "bc", "bb"]);
  });
});

const schemaNodes: UpstreamSchemaNode[] = [
  {
    nodeId: "n1",
    label: "Fetch",
    source: "lastRun",
    paths: [
      { path: "items", type: "array", sample: null },
      { path: "items[0]", type: "object", sample: null },
      { path: "items[0].price", type: "number", sample: "12" },
      { path: "items[0].sku", type: "string", sample: "A1" },
      { path: "total", type: "number", sample: "12" },
      { path: "first name", type: "string", sample: "x" },
    ],
  },
  { nodeId: "n2", label: "Say \"hi\"", source: "pinned", paths: [{ path: "msg", type: "string", sample: "hey" }] },
  { nodeId: "n3", label: "Empty", source: "none", paths: [] },
];
const fns: ExpressionFunctionDoc[] = [
  { name: "upper", category: "string", signature: "upper(s)", doc: "Uppercase", example: 'upper("a")' },
  { name: "toNumber", category: "number", signature: "toNumber(x)", doc: "To number", example: 'toNumber("1")' },
  { name: "filter", category: "array", signature: "filter(a, fn)", doc: "Filter", example: "filter(a, x => x)" },
];
const src: CompletionSources = { nodes: schemaNodes, functions: fns };
const ctxAt = (t: string) => {
  const cursor = t.indexOf("|");
  const text = t.replace("|", "");
  return { text, ctx: completionContextAt(text, cursor, true)! };
};
const labels = (t: string) => buildCompletions(ctxAt(t).ctx, src).map((i) => i.label);

describe("buildCompletions", () => {
  it("offers roots, functions, keywords and legacy top-level fields at term position", () => {
    const all = labels("{{ | }}");
    for (const l of ["$json", "$node", "$now", "upper", "true", "items", "total"]) expect(all).toContain(l);
  });

  it("filters by prefix and ranks the root first for $-prefixes", () => {
    expect(labels("{{ $j| }}")[0]).toBe("$json");
    expect(labels("{{ upp| }}")[0]).toBe("upper");
  });

  it("$node inserts a quoted bracket and asks to reopen", () => {
    const item = buildCompletions(ctxAt("{{ $no| }}").ctx, src).find((i) => i.label === "$node")!;
    expect(item).toMatchObject({ label: "$node", insertText: '$node[""]', caretBack: 2, reopen: true });
  });

  it("offers upstream labels inside $node[\"", () => {
    const out = labels('{{ $node["| }}');
    expect(out).toEqual(expect.arrayContaining(["Fetch", 'Say "hi"', "Empty"]));
  });

  it("escapes labels for the active quote", () => {
    const item = buildCompletions(ctxAt('{{ $node["Sa| }}').ctx, src)[0];
    expect(item.insertText).toBe('Say \\"hi\\"');
    const single = buildCompletions(ctxAt("{{ $node['Sa| }}").ctx, src)[0];
    expect(single.insertText).toBe('Say "hi"');
  });

  it("offers fields of the referenced node after $node[\"X\"].", () => {
    const out = labels('{{ $node["Fetch"].| }}');
    expect(out).toEqual(expect.arrayContaining(["items", "total", "first name", "output", "status", "summary"]));
    expect(out).not.toContain("msg");
  });

  it("descends into indexed paths", () => {
    const out = labels('{{ $node["Fetch"].items[0].| }}');
    expect(out.slice(0, 2).sort()).toEqual(["price", "sku"]);
  });

  it("does not offer meta members below the node root", () => {
    expect(labels('{{ $node["Fetch"].items[0].| }}')).not.toContain("output");
  });

  it("unknown node labels still offer functions only", () => {
    const out = labels('{{ $node["Nope"].| }}');
    expect(out).toEqual(expect.arrayContaining(["upper"]));
    expect(out).not.toContain("items");
  });

  it("resolves $node.id() references", () => {
    const item = labels('{{ $node.id("n2").| }}');
    expect(item).toContain("msg");
  });

  it("offers id after $node.", () => {
    expect(labels("{{ $node.| }}")[0]).toBe("id");
  });

  it("completes $json members from the union of upstream shapes", () => {
    const out = labels("{{ $json.| }}");
    expect(out).toEqual(expect.arrayContaining(["items", "total", "msg"]));
  });

  it("completes bare-root members", () => {
    expect(labels("{{ items[0].| }}")).toEqual(expect.arrayContaining(["price", "sku"]));
  });

  it("offers $run and $workflow members", () => {
    expect(labels("{{ $run.| }}")).toEqual(expect.arrayContaining(["id", "startedAt", "mode", "requestedBy"]));
    expect(labels("{{ $workflow.| }}")).toEqual(expect.arrayContaining(["id", "name", "version"]));
  });

  it("offers functions as methods after a dot", () => {
    expect(labels("{{ name.up| }}")).toContain("upper");
  });

  it("functions carry signature, doc, example and an in-paren caret", () => {
    const item = buildCompletions(ctxAt("{{ upp| }}").ctx, src)[0];
    expect(item).toMatchObject({ detail: "upper(s)", doc: "Uppercase", example: 'upper("a")', insertText: "upper()", caretBack: 1 });
  });

  it("works with no schema and no functions", () => {
    const out = buildCompletions(ctxAt("{{ | }}").ctx, { nodes: [], functions: [] }).map((i) => i.label);
    expect(out).toContain("$json");
    expect(out).not.toContain("upper");
  });

  it("brackets member names that are not identifiers", () => {
    const item = buildCompletions(ctxAt('{{ $node["Fetch"].first| }}').ctx, src)[0];
    expect(item).toMatchObject({ label: "first name", insertText: '["first name"]', replaceDot: true });
  });
});

describe("applyCompletion", () => {
  const pick = (t: string, label: string) => {
    const { text, ctx } = ctxAt(t);
    const item = buildCompletions(ctx, src).find((i) => i.label === label)!;
    return { text, ctx, item, res: applyCompletion(text, ctx, item) };
  };

  it("replaces the typed prefix and moves the caret after it", () => {
    const { res } = pick("say {{ $js| }}", "$json");
    expect(res.value).toBe("say {{ $json }}");
    expect(res.value.slice(0, res.cursor)).toBe("say {{ $json");
  });

  it("replaces the whole word when completing mid-word", () => {
    const { res } = pick("{{ up|per }}", "upper");
    expect(res.value).toBe("{{ upper() }}");
  });

  it("puts the caret inside function parens", () => {
    const { res } = pick("{{ upp| }}", "upper");
    expect(res.value.slice(0, res.cursor)).toBe("{{ upper(");
  });

  it("$node becomes $node[\"\"] with the caret between the quotes and reopen set", () => {
    const { res } = pick("{{ $no| }}", "$node");
    expect(res.value).toBe('{{ $node[""] }}');
    expect(res.value.slice(0, res.cursor)).toBe('{{ $node["');
    expect(res.reopen).toBe(true);
  });

  it("chaining $node then a label yields a valid reference", () => {
    const a = pick("{{ $no| }}", "$node");
    const cursorCtx = completionContextAt(a.res.value, a.res.cursor, true)!;
    expect(cursorCtx.kind).toBe("node-label");
    const item = buildCompletions(cursorCtx, src).find((i) => i.label === "Fetch")!;
    const b = applyCompletion(a.res.value, cursorCtx, item);
    expect(b.value).toBe('{{ $node["Fetch"] }}');
    expect(b.value.slice(0, b.cursor)).toBe('{{ $node["Fetch"]');
  });

  it("closes the string and bracket when the label had no closing quote", () => {
    const { res } = pick('{{ $node["Fe| }}', "Fetch");
    expect(res.value).toBe('{{ $node["Fetch"] }}');
    expect(res.value.slice(0, res.cursor)).toBe('{{ $node["Fetch"]');
  });

  it("replaces an existing label in place", () => {
    const { res } = pick('{{ $node["Fe|tch"].x }}', "Fetch");
    expect(res.value).toBe('{{ $node["Fetch"].x }}');
    expect(res.value.slice(0, res.cursor)).toBe('{{ $node["Fetch"]');
  });

  it("inserts a label containing quotes as a valid literal", () => {
    const { res } = pick('{{ $node["Sa| }}', 'Say "hi"');
    expect(res.value).toBe('{{ $node["Say \\"hi\\""] }}');
    expect(parseChain('$node["Say \\"hi\\""]')?.node?.value).toBe('Say "hi"');
  });

  it("member completion replaces only the partial member", () => {
    const { res } = pick('{{ $node["Fetch"].it| }}', "items");
    expect(res.value).toBe('{{ $node["Fetch"].items }}');
  });

  it("bracket members swallow the dot", () => {
    const { res } = pick('{{ $node["Fetch"].first| }}', "first name");
    expect(res.value).toBe('{{ $node["Fetch"]["first name"] }}');
  });

  it("leaves text after the segment untouched", () => {
    const { res } = pick("a {{ upp| }} b {{ c }}", "upper");
    expect(res.value).toBe("a {{ upper() }} b {{ c }}");
  });
});
