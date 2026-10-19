import { describe, expect, it } from "vitest";
import {
  BARE_PREFIX_LEN,
  buildSchemaTree,
  buildNodeRef,
  buildJsonPathExpression,
  buildNodeExpression,
  buildPathExpression,
  childrenOf,
  findSegments,
  formatAccess,
  insertExpressionAt,
  parseChain,
  parsePath,
  renameNodeLabelInExpression,
  renameNodeLabelInTemplate,
  wrapBare,
} from "./expressionLang";

describe("path parsing and formatting", () => {
  it("parses dotted and indexed paths", () => {
    expect(parsePath("items[0].price")).toEqual([
      { t: "key", v: "items" },
      { t: "idx", v: 0 },
      { t: "key", v: "price" },
    ]);
  });

  it("parses bracketed string keys", () => {
    expect(parsePath('a["b c"].d')).toEqual([
      { t: "key", v: "a" },
      { t: "key", v: "b c" },
      { t: "key", v: "d" },
    ]);
  });

  it("is tolerant of odd input", () => {
    expect(parsePath("")).toEqual([]);
    expect(() => parsePath("a[")).not.toThrow();
    expect(() => parsePath('a["x')).not.toThrow();
    expect(parsePath("first name")).toEqual([{ t: "key", v: "first name" }]);
  });

  it("formats identifier keys with dots and others with brackets", () => {
    expect(formatAccess(parsePath("items[0].price"))).toBe(".items[0].price");
    expect(formatAccess(parsePath("first name"))).toBe('["first name"]');
    expect(formatAccess(parsePath("a.b-c"))).toBe('.a["b-c"]');
    expect(formatAccess(parsePath("true"))).toBe('["true"]');
  });
});

describe("path insertion helpers", () => {
  it("builds the canonical node path expression", () => {
    expect(buildPathExpression("Fetch", "items[0].price")).toBe('$node["Fetch"].items[0].price');
  });

  it("quotes labels safely", () => {
    expect(buildPathExpression('Say "hi"', "a")).toBe('$node["Say \\"hi\\""].a');
    expect(buildPathExpression("it's a\\b", "a")).toBe('$node["it\'s a\\\\b"].a');
    expect(buildNodeExpression("Get user")).toBe('$node["Get user"]');
  });

  it("brackets keys that are not identifiers", () => {
    expect(buildPathExpression("N", "user.first name")).toBe('$node["N"].user["first name"]');
  });

  it("builds $json paths", () => {
    expect(buildJsonPathExpression("items[2].sku")).toBe("$json.items[2].sku");
    expect(buildJsonPathExpression("")).toBe("$json");
  });

  it("generated expressions survive a parse round-trip", () => {
    const e = buildPathExpression('A "b"', "list[1].k y");
    const ref = parseChain(e)!;
    expect(ref.node).toEqual({ by: "label", value: 'A "b"' });
    expect(ref.path).toBe('list[1]["k y"]');
  });

  it("wrapBare wraps in one segment whose offset is BARE_PREFIX_LEN", () => {
    const w = wrapBare("a > 1");
    expect(w).toBe("{{ a > 1 }}");
    expect(findSegments(w)[0].innerStart + 1).toBe(BARE_PREFIX_LEN);
  });
});

describe("insertExpressionAt", () => {
  it("wraps in braces when outside any segment", () => {
    expect(insertExpressionAt("Hello ", 6, 6, "$json.a")).toEqual({ value: "Hello {{ $json.a }}", cursor: 19 });
  });

  it("inserts raw inside a segment", () => {
    const t = "x {{ upper() }}";
    const r = insertExpressionAt(t, 11, 11, "$json.a");
    expect(r.value).toBe("x {{ upper($json.a) }}");
    expect(r.cursor).toBe(18);
  });

  it("replaces a selection", () => {
    const r = insertExpressionAt("abc def", 0, 3, "$json.x");
    expect(r.value).toBe("{{ $json.x }} def");
  });

  it("concatenates when the caret is inside a string literal of a segment", () => {
    const t = '{{ "Hi  " }}';
    const r = insertExpressionAt(t, 7, 7, "$json.n");
    expect(r.value).toBe('{{ "Hi " + $json.n + " " }}');
  });

  it("clamps out-of-range positions", () => {
    expect(insertExpressionAt("ab", 99, 99, "x").value).toBe("ab{{ x }}");
  });

  it("puts the caret right after the inserted text", () => {
    const r = insertExpressionAt("a{{ }}b", 4, 4, "z");
    expect(r.value.slice(0, r.cursor)).toBe("a{{ z");
  });
});

describe("renameNodeLabelInExpression", () => {
  it("rewrites matching references", () => {
    expect(renameNodeLabelInExpression('$node["Old"].a + $node["Old"].b', "Old", "New")).toBe('$node["New"].a + $node["New"].b');
  });

  it("keeps single-quote style and escapes for it", () => {
    expect(renameNodeLabelInExpression("$node['Old'].a", "Old", "It's")).toBe("$node['It\\'s'].a");
  });

  it("escapes the new label for double quotes", () => {
    expect(renameNodeLabelInExpression('$node["Old"]', "Old", 'Say "x"')).toBe('$node["Say \\"x\\""]');
  });

  it("tolerates whitespace inside the brackets", () => {
    expect(renameNodeLabelInExpression('$node[ "Old" ].a', "Old", "New")).toBe('$node[ "New" ].a');
  });

  it("matches labels that need unescaping", () => {
    expect(renameNodeLabelInExpression('$node["Say \\"x\\""].a', 'Say "x"', "Y")).toBe('$node["Y"].a');
  });

  it("does not touch other labels, id form, or lookalike strings", () => {
    const src = '$node["Other"].a + "$node[\\"Old\\"]" + $node.id("Old").b';
    expect(renameNodeLabelInExpression(src, "Old", "New")).toBe(src);
  });

  it("does not touch a different root indexed with the same string", () => {
    expect(renameNodeLabelInExpression('$json["Old"]', "Old", "New")).toBe('$json["Old"]');
  });

  it("is a no-op for empty or unchanged labels", () => {
    expect(renameNodeLabelInExpression('$node["A"]', "", "B")).toBe('$node["A"]');
    expect(renameNodeLabelInExpression('$node["A"]', "A", "A")).toBe('$node["A"]');
  });
});

describe("renameNodeLabelInTemplate", () => {
  it("rewrites only inside segments", () => {
    const t = 'Old: $node["Old"] {{ $node["Old"].x }} and {{ y }}';
    expect(renameNodeLabelInTemplate(t, "Old", "New")).toBe('Old: $node["Old"] {{ $node["New"].x }} and {{ y }}');
  });

  it("handles several segments and returns the same string when nothing matches", () => {
    const t = '{{ $node["A"].x }}{{ $node["A"].y }}';
    expect(renameNodeLabelInTemplate(t, "A", "B")).toBe('{{ $node["B"].x }}{{ $node["B"].y }}');
    expect(renameNodeLabelInTemplate(t, "Z", "B")).toBe(t);
  });

  it("does not corrupt text around braces in strings", () => {
    const t = '{{ "}}" + $node["A"].x }} tail }}';
    expect(renameNodeLabelInTemplate(t, "A", "B")).toBe('{{ "}}" + $node["B"].x }} tail }}');
  });

  it("survives an unclosed segment", () => {
    expect(renameNodeLabelInTemplate('{{ $node["A"].x', "A", "B")).toBe('{{ $node["B"].x');
  });
});

describe("parseChain", () => {
  it("parses $node label chains", () => {
    expect(parseChain('$node["A"].items[0].price')).toEqual({
      root: "$node",
      node: { by: "label", value: "A" },
      path: "items[0].price",
    });
  });

  it("parses $node.id chains", () => {
    expect(parseChain('$node.id("n7").x')).toEqual({ root: "$node", node: { by: "id", value: "n7" }, path: "x" });
  });

  it("parses $json and bare roots", () => {
    expect(parseChain("$json.a.b")).toEqual({ root: "$json", node: undefined, path: "a.b" });
    expect(parseChain("order.lines[1]")).toEqual({ root: "order", node: undefined, path: "lines[1]" });
    expect(parseChain("$json")).toEqual({ root: "$json", node: undefined, path: "" });
  });

  it("accepts ?. and bracket strings", () => {
    expect(parseChain('$json?.a["b c"]')?.path).toBe('a["b c"]');
  });

  it("rejects dynamic or malformed chains", () => {
    expect(parseChain("$json[i]")).toBeNull();
    expect(parseChain("f(x).y")).toBeNull();
    expect(parseChain("")).toBeNull();
    expect(parseChain('$node["A"')).toBeNull();
    expect(parseChain("$node.x")).toBeNull();
  });
});

describe("childrenOf", () => {
  const paths = [
    { path: "items", type: "array" },
    { path: "items[0]", type: "object" },
    { path: "items[0].price", type: "number" },
    { path: "items[0].sku", type: "string" },
    { path: "customer", type: "object" },
    { path: "customer.name", type: "string" },
    { path: "customer.first name", type: "string" },
  ];

  it("lists top-level keys with their types", () => {
    expect(childrenOf(paths, "")).toEqual([
      { name: "items", type: "array" },
      { name: "customer", type: "object" },
    ]);
  });

  it("lists keys under an indexed base", () => {
    expect(childrenOf(paths, "items[0]").map((c) => c.name)).toEqual(["price", "sku"]);
  });

  it("does not offer array indices as member names", () => {
    expect(childrenOf(paths, "items")).toEqual([]);
  });

  it("does not confuse a shared name prefix", () => {
    expect(childrenOf([{ path: "customerName", type: "string" }], "customer")).toEqual([]);
  });

  it("infers a null type when only deeper paths are known", () => {
    expect(childrenOf([{ path: "a.b", type: "string" }], "")).toEqual([{ name: "a", type: null }]);
  });

  it("keeps keys that are not identifiers", () => {
    expect(childrenOf(paths, "customer").map((c) => c.name)).toEqual(["name", "first name"]);
  });
});

describe("buildSchemaTree", () => {
  it("nests flat paths and creates missing intermediates", () => {
    const tree = buildSchemaTree([
      { path: "items[0].price", type: "number", sample: "9" },
      { path: "items[1].price", type: "number", sample: "12" },
      { path: "id", type: "string", sample: "a1" },
    ]);
    expect(tree.map((n) => n.name)).toEqual(["items", "id"]);
    const items = tree[0];
    expect(items.path).toBe("items");
    expect(items.type).toBeNull();
    expect(items.children.map((n) => n.name)).toEqual(["[0]", "[1]"]);
    expect(items.children[1].children[0]).toMatchObject({ name: "price", path: "items[1].price", type: "number", sample: "12" });
  });

  it("fills in an intermediate's type when the server lists it later", () => {
    const tree = buildSchemaTree([
      { path: "a.b", type: "string", sample: null },
      { path: "a", type: "object", sample: null },
    ]);
    expect(tree).toHaveLength(1);
    expect(tree[0].type).toBe("object");
    expect(tree[0].children[0].path).toBe("a.b");
  });

  it("orders array indices numerically and quotes awkward keys in paths", () => {
    const tree = buildSchemaTree([
      { path: "rows[10]", type: "string" },
      { path: "rows[2]", type: "string" },
      { path: 'h["X-Id"]', type: "string" },
    ]);
    expect(tree[0].children.map((n) => n.name)).toEqual(["[2]", "[10]"]);
    expect(tree[1].children[0]).toMatchObject({ name: "X-Id", path: 'h["X-Id"]' });
  });

  it("ignores empty paths and never throws", () => {
    expect(buildSchemaTree([{ path: "" }, { path: "..." }])).toEqual([]);
  });
});

describe("buildNodeRef", () => {
  it("uses the label form by default and quotes safely", () => {
    expect(buildNodeRef({ nodeId: "n1", label: "Fetch" }, "items[0].price")).toBe('$node["Fetch"].items[0].price');
    expect(buildNodeRef({ nodeId: "n1", label: 'Say "hi"' })).toBe('$node["Say \\"hi\\""]');
  });

  it("falls back to the id form for unlabeled or ambiguous nodes", () => {
    expect(buildNodeRef({ nodeId: "n7", label: "" }, "a")).toBe('$node.id("n7").a');
    expect(buildNodeRef({ nodeId: "n7", label: "Dup" }, "", true)).toBe('$node.id("n7")');
  });
});
