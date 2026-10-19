import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import { EXPRESSION_FIELDS, NODE_CATEGORIES, NODE_DEFS, expressionFieldsOf, nodeDef, nodeDescriptionKey, nodeLabelKey } from "./nodeDefs";
import {
  AGGREGATE_OPERATIONS,
  COMBINATORS,
  CONDITION_OPERATORS,
  DATA_CATEGORY,
  DATA_NODE_TYPES,
  JOIN_TYPES,
  KEEP_CHOICES,
  MERGE_MODES,
  SET_VALUE_TYPES,
  SORT_DIRECTIONS,
  SORT_TYPES,
  SWITCH_DEFAULT_HANDLE,
  caseNameProblem,
  dataNodeMissing,
  isDataNodeDefKey,
  isDataNodeType,
  newAggregate,
  newAssignment,
  newCase,
  newCondition,
  newSortKey,
  operatorTakesValue,
  parseCountInput,
  patchAt,
  removeAt,
  splitFieldList,
  summarizeDataNode,
  switchCaseNames,
  switchPorts,
  switchPrimaryHandle,
  type SwitchCase,
} from "./dataNodes";

const ns = (en as any).workflowNodesData as Record<string, string>;
// A translator that exposes what was asked for: "key" or "key{json values}".
const t = (key: string, values?: Record<string, string | number>) => (values ? `${key}${JSON.stringify(values)}` : key);

const cond = (field = "p", operator = "eq", value = "1") => ({ field, operator, value });
const aCase = (name: string, conditions = [cond()]): SwitchCase => ({ name, combinator: "and", conditions });

describe("data node registration", () => {
  it("registers all eight types as supported palette entries in the Data category", () => {
    expect([...DATA_NODE_TYPES]).toEqual(["set", "filter", "sort", "aggregate", "limit", "dedupe", "switch", "merge"]);
    for (const type of DATA_NODE_TYPES) {
      const def = NODE_DEFS.find((d) => d.type === type);
      expect(def, type).toBeDefined();
      expect(def!.supported).toBe(true);
      expect(def!.category).toBe(DATA_CATEGORY);
      expect(def!.icon).toBeTruthy();
      expect(nodeDef(type)).toBe(def);
      expect(isDataNodeType(type)).toBe(true);
    }
    expect(isDataNodeType("agent")).toBe(false);
    expect(isDataNodeType(undefined)).toBe(false);
    expect(NODE_CATEGORIES).toContain(DATA_CATEGORY);
  });

  it("node type ids are unique across the palette", () => {
    const types = NODE_DEFS.map((d) => d.type);
    expect(new Set(types).size).toBe(types.length);
  });

  it("lists the expression fields core resolves, and nothing else for the pure-structure nodes", () => {
    expect(EXPRESSION_FIELDS.set).toEqual(["assignments[].value"]);
    expect(EXPRESSION_FIELDS.filter).toEqual(["conditions[].value"]);
    expect(EXPRESSION_FIELDS.switch).toEqual(["cases[].conditions[].value"]);
    expect(EXPRESSION_FIELDS.limit).toEqual(["count"]);
    expect(EXPRESSION_FIELDS.merge).toEqual(["sources[]"]);
    for (const type of ["sort", "aggregate", "dedupe"]) expect(expressionFieldsOf(type)).toEqual([]);
  });

  it("every default config is well-formed except the blank-field starters the author must fill in", () => {
    for (const type of DATA_NODE_TYPES) {
      const problem = dataNodeMissing(type, nodeDef(type).defaultConfig);
      // set/filter start with one empty row: set needs a field name, filter is runnable as-is.
      expect(problem, type).toBe(type === "set" ? "noFields" : null);
    }
  });

  it("has palette strings for every type and the category in the workflowNodesData namespace", () => {
    expect(ns).toBeTruthy();
    for (const type of DATA_NODE_TYPES) {
      expect(ns[nodeLabelKey(type)], type).toBeTruthy();
      expect(ns[nodeDescriptionKey(type)], type).toBeTruthy();
      expect(isDataNodeDefKey(nodeLabelKey(type))).toBe(true);
      expect(isDataNodeDefKey(nodeDescriptionKey(type))).toBe(true);
    }
    expect(ns.categoryData).toBeTruthy();
    expect(isDataNodeDefKey("categoryData")).toBe(true);
    // existing nodeDefs keys must keep resolving from their own namespace
    expect(isDataNodeDefKey("agentLabel")).toBe(false);
    expect(isDataNodeDefKey("categoryLogic")).toBe(false);
  });
});

describe("i18n completeness", () => {
  const read = (file: string) => readFileSync(fileURLToPath(new URL(file, import.meta.url)), "utf8");

  it("every literal t(...) key used by the forms and card summaries exists in en.json", () => {
    const keys = new Set<string>();
    for (const file of ["./DataNodeConfigs.tsx", "./dataNodes.ts"]) {
      for (const m of read(file).matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
    }
    // first arguments that are expressions (ternaries / template strings) the regex above cannot see
    for (const k of ["summarySetOnly", "summarySet", "summaryLimitLast", "summaryLimitFirst", "caseReservedWarn", "caseDuplicateWarn"]) keys.add(k);
    expect(keys.size).toBeGreaterThan(40);
    const missing = [...keys].filter((k) => !ns[k]);
    expect(missing).toEqual([]);
  });

  it("every option label the forms build from a prefix exists in en.json", () => {
    const groups: Array<[string, readonly string[]]> = [
      ["op_", CONDITION_OPERATORS],
      ["type_", SET_VALUE_TYPES],
      ["combinator_", COMBINATORS],
      ["dir_", SORT_DIRECTIONS],
      ["sortType_", SORT_TYPES],
      ["agg_", AGGREGATE_OPERATIONS],
      ["mode_", KEEP_CHOICES],
      ["keep_", KEEP_CHOICES],
      ["mergeMode_", MERGE_MODES],
      ["join_", JOIN_TYPES],
    ];
    const missing: string[] = [];
    for (const [prefix, values] of groups) for (const v of values) if (!ns[`${prefix}${v}`]) missing.push(`${prefix}${v}`);
    // card subtitles built as summaryMerge_<mode> for the non-join modes
    for (const mode of MERGE_MODES) if (mode !== "join" && !ns[`summaryMerge_${mode}`]) missing.push(`summaryMerge_${mode}`);
    expect(missing).toEqual([]);
  });
});

describe("switch ports", () => {
  it("lists usable case names in order, dropping blank, reserved and duplicate (case-insensitive) names", () => {
    const cases = [aCase("Big"), aCase(" "), aCase("default"), aCase("ERROR"), aCase("big"), aCase("vip")];
    expect(switchCaseNames({ cases })).toEqual(["Big", "vip"]);
    expect(switchCaseNames(undefined)).toEqual([]);
  });

  it("always ends with the fixed default port", () => {
    const ports = switchPorts({ cases: [aCase("a"), aCase("b")] }, t);
    expect(ports.map((p) => p.id)).toEqual(["a", "b", SWITCH_DEFAULT_HANDLE]);
    expect(ports[2].title).toBe("switchDefaultPortTitle");
    expect(switchPorts(undefined, t).map((p) => p.id)).toEqual([SWITCH_DEFAULT_HANDLE]);
  });

  it("picks the first case as the primary handle, else the default", () => {
    expect(switchPrimaryHandle({ cases: [aCase("first"), aCase("second")] })).toBe("first");
    expect(switchPrimaryHandle({ cases: [] })).toBe(SWITCH_DEFAULT_HANDLE);
    expect(switchPrimaryHandle(undefined)).toBe(SWITCH_DEFAULT_HANDLE);
  });

  it("names a new case with the smallest free case-N", () => {
    expect(newCase([]).name).toBe("case-1");
    expect(newCase([aCase("case-1")]).name).toBe("case-2");
    expect(newCase([aCase("case-2"), aCase("x")]).name).toBe("case-3");
    expect(newCase([aCase("Case-3"), aCase("a"), aCase("b")]).name).toBe("case-4");
    expect(newCase(undefined).conditions).toHaveLength(1);
  });

  it("flags a reserved or repeated case name only", () => {
    const cases = [aCase("a"), aCase("A"), aCase("default"), aCase(""), aCase("b")];
    expect(caseNameProblem(cases, 0)).toBeNull();
    expect(caseNameProblem(cases, 1)).toBe("duplicate");
    expect(caseNameProblem(cases, 2)).toBe("reserved");
    expect(caseNameProblem(cases, 3)).toBeNull();
    expect(caseNameProblem(cases, 4)).toBeNull();
    expect(caseNameProblem(undefined, 0)).toBeNull();
  });
});

describe("dataNodeMissing (client twin of the server's save-time checks)", () => {
  it("set needs at least one named field", () => {
    expect(dataNodeMissing("set", {})).toBe("noFields");
    expect(dataNodeMissing("set", { assignments: [{ name: " ", value: "1", type: "auto" }] })).toBe("noFields");
    expect(dataNodeMissing("set", { assignments: [{ name: "a", value: "", type: "auto" }] })).toBeNull();
  });

  it("filter needs conditions with known operators and a regex within the cap", () => {
    expect(dataNodeMissing("filter", {})).toBe("noConditions");
    expect(dataNodeMissing("filter", { conditions: [cond("p", "wobbles")] })).toBe("badOperator");
    expect(dataNodeMissing("filter", { conditions: [cond("p", "regex", "a".repeat(201))] })).toBe("badRegex");
    expect(dataNodeMissing("filter", { conditions: [cond("p", "regex", "a".repeat(200))] })).toBeNull();
    // an expression is not known until run time
    expect(dataNodeMissing("filter", { conditions: [cond("p", "regex", `${"a".repeat(300)}{{ x }}`)] })).toBeNull();
    expect(dataNodeMissing("filter", { conditions: [cond("p", "gt", "1")] })).toBeNull();
  });

  it("sort, aggregate and limit need their one required thing", () => {
    expect(dataNodeMissing("sort", {})).toBe("noSortKey");
    expect(dataNodeMissing("sort", { sortKeys: [newSortKey()] })).toBeNull();
    expect(dataNodeMissing("aggregate", {})).toBe("noAggregation");
    expect(dataNodeMissing("aggregate", { aggregations: [newAggregate()] })).toBeNull();
    expect(dataNodeMissing("limit", {})).toBe("noCount");
    expect(dataNodeMissing("limit", { count: "  " })).toBe("noCount");
    expect(dataNodeMissing("limit", { count: 0 })).toBeNull();
    expect(dataNodeMissing("limit", { count: "{{ $json.n }}" })).toBeNull();
  });

  it("switch checks case names and each case's conditions", () => {
    expect(dataNodeMissing("switch", {})).toBe("noCases");
    expect(dataNodeMissing("switch", { cases: [aCase("")] })).toBe("caseNoName");
    expect(dataNodeMissing("switch", { cases: [aCase("Default")] })).toBe("caseReserved");
    expect(dataNodeMissing("switch", { cases: [aCase("a"), aCase("A")] })).toBe("caseDuplicate");
    expect(dataNodeMissing("switch", { cases: [aCase("a", [])] })).toBe("caseNoConditions");
    expect(dataNodeMissing("switch", { cases: [aCase("a", [cond("p", "nope")])] })).toBe("badOperator");
    expect(dataNodeMissing("switch", { cases: [aCase("a"), aCase("b")] })).toBeNull();
  });

  it("merge in join mode needs a key; other modes need nothing", () => {
    expect(dataNodeMissing("merge", {})).toBeNull();
    expect(dataNodeMissing("merge", { mode: "join" })).toBe("noJoinKey");
    expect(dataNodeMissing("merge", { mode: "join", key: "id" })).toBeNull();
    expect(dataNodeMissing("merge", { mode: "first_non_empty" })).toBeNull();
    expect(dataNodeMissing("dedupe", {})).toBeNull();
    expect(dataNodeMissing("trigger", {})).toBeNull();
  });
});

describe("summarizeDataNode", () => {
  it("nudges toward what is missing, else describes the setup", () => {
    expect(summarizeDataNode("set", {}, t)).toBe("summarySetNone");
    expect(summarizeDataNode("set", { assignments: [{ name: "a", value: "", type: "auto" }, newAssignment()] }, t)).toBe('summarySet{"count":1}');
    expect(summarizeDataNode("set", { keepOnlySet: true, assignments: [{ name: "a", value: "", type: "auto" }] }, t)).toBe('summarySetOnly{"count":1}');
    expect(summarizeDataNode("filter", {}, t)).toBe("summaryFilterNone");
    expect(summarizeDataNode("filter", { conditions: [cond(), cond()] }, t)).toBe('summaryFilter{"count":2}');
    expect(summarizeDataNode("sort", {}, t)).toBe("summarySortNone");
    expect(summarizeDataNode("sort", { sortKeys: [{ field: "price", direction: "desc", type: "auto" }] }, t)).toBe('summarySort{"field":"price","direction":"↓"}');
    expect(summarizeDataNode("sort", { sortKeys: [{ field: "", direction: "asc", type: "auto" }] }, t)).toBe('summarySort{"field":"summaryWholeItem","direction":"↑"}');
    expect(summarizeDataNode("aggregate", {}, t)).toBe("summaryAggregateNone");
    expect(summarizeDataNode("aggregate", { aggregations: [newAggregate()] }, t)).toBe('summaryAggregate{"count":1}');
    expect(summarizeDataNode("limit", {}, t)).toBe("summaryLimitNone");
    expect(summarizeDataNode("limit", { count: 5 }, t)).toBe('summaryLimitFirst{"count":"5"}');
    expect(summarizeDataNode("limit", { count: 5, mode: "last" }, t)).toBe('summaryLimitLast{"count":"5"}');
    expect(summarizeDataNode("dedupe", {}, t)).toBe("summaryDedupeWhole");
    expect(summarizeDataNode("dedupe", { dedupeBy: " id , email " }, t)).toBe('summaryDedupeBy{"fields":"id, email"}');
    expect(summarizeDataNode("switch", {}, t)).toBe("summarySwitchNone");
    expect(summarizeDataNode("switch", { cases: [aCase("a"), aCase("b"), aCase("b")] }, t)).toBe('summarySwitch{"count":2}');
    expect(summarizeDataNode("merge", {}, t)).toBe("summaryMerge_append");
    expect(summarizeDataNode("merge", { mode: "first_non_empty" }, t)).toBe("summaryMerge_first_non_empty");
    expect(summarizeDataNode("merge", { mode: "join" }, t)).toBe("summaryMergeNoKey");
    expect(summarizeDataNode("merge", { mode: "join", key: "id" }, t)).toBe('summaryMergeJoin{"key":"id"}');
    expect(summarizeDataNode("merge", { mode: "bogus" }, t)).toBe("summaryMerge_append");
    expect(summarizeDataNode("agent", {}, t)).toBe("");
  });

  it("clips a long sort field and dedupe list so the card stays one line", () => {
    const long = "a".repeat(60);
    const sort = summarizeDataNode("sort", { sortKeys: [{ field: long, direction: "asc", type: "auto" }] }, t);
    expect(sort.length).toBeLessThan(80);
    expect(sort).toContain("…");
    const dedupe = summarizeDataNode("dedupe", { dedupeBy: long }, t);
    expect(dedupe).toContain("…");
  });
});

describe("form helpers", () => {
  it("row factories give valid starting rows", () => {
    expect(newCondition()).toEqual({ field: "", operator: "eq", value: "" });
    expect(newAssignment()).toEqual({ name: "", value: "", type: "auto" });
    expect(newSortKey()).toEqual({ field: "", direction: "asc", type: "auto" });
    expect(newAggregate()).toEqual({ operation: "count", field: "", as: "" });
  });

  it("patchAt / removeAt never mutate and tolerate a missing list", () => {
    const rows = [{ a: 1 }, { a: 2 }];
    const patched = patchAt(rows, 1, { a: 9 });
    expect(patched).toEqual([{ a: 1 }, { a: 9 }]);
    expect(rows).toEqual([{ a: 1 }, { a: 2 }]);
    expect(removeAt(rows, 0)).toEqual([{ a: 2 }]);
    expect(rows).toHaveLength(2);
    expect(patchAt(undefined, 0, {})).toEqual([]);
    expect(removeAt(undefined, 0)).toEqual([]);
  });

  it("hides the value box only for the operators that ignore it", () => {
    expect(operatorTakesValue("empty")).toBe(false);
    expect(operatorTakesValue("notEmpty")).toBe(false);
    expect(operatorTakesValue("eq")).toBe(true);
    expect(operatorTakesValue("regex")).toBe(true);
    expect(operatorTakesValue(undefined)).toBe(true);
  });

  it("parses the limit box: digits become a number, anything else stays text", () => {
    expect(parseCountInput("25")).toBe(25);
    expect(parseCountInput(" 7 ")).toBe(7);
    expect(parseCountInput("")).toBe("");
    expect(parseCountInput("{{ $json.n }}")).toBe("{{ $json.n }}");
    expect(parseCountInput("-1")).toBe("-1");
    expect(parseCountInput("1.5")).toBe("1.5");
  });

  it("splits a comma separated field list like the server does", () => {
    expect(splitFieldList(" id , ,email,")).toEqual(["id", "email"]);
    expect(splitFieldList("")).toEqual([]);
    expect(splitFieldList(undefined)).toEqual([]);
  });
});

describe("canvas integration", () => {
  it("labels a Switch edge by its case name, like a supervisor route", async () => {
    const { edgeLabelFor } = await import("./runOverlay");
    expect(edgeLabelFor({ sourceHandle: "big" }, { type: "switch" })).toBe("big");
    expect(edgeLabelFor({ sourceHandle: SWITCH_DEFAULT_HANDLE }, { type: "switch" })).toBe(SWITCH_DEFAULT_HANDLE);
    expect(edgeLabelFor({ sourceHandle: "error" }, { type: "switch" })).toBe("error");
    expect(edgeLabelFor({ sourceHandle: null }, { type: "switch" })).toBeUndefined();
  });
});
