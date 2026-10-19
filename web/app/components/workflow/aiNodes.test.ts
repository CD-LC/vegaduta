import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import { EXPRESSION_FIELDS, NODE_DEFS, nodeDef, nodeDescriptionKey, nodeLabelKey } from "./nodeDefs";
import { edgeLabelFor } from "./runOverlay";
import {
  AI_DOC_FORMATS,
  AI_NODE_TYPES,
  AI_PARAM_TYPES,
  AI_PROBLEM_CODES,
  aggregatorProblems,
  aiNodeDefaultConfig,
  aiNodeProblems,
  aiNodeSummary,
  classifierEdgeLabel,
  classifierPorts,
  classifierProblems,
  isAiNodeDefKey,
  isAiNodeType,
  newClass,
  newParam,
  optionalInt,
  optionsText,
  parameterProblems,
  parseOptionsText,
  readClasses,
  readParams,
  readSources,
  removeAt,
  replaceAt,
  setAt,
  slugHandle,
  uniqueHandle,
  updateAt,
  withParamType,
  type AiNodeConfig,
  type AiParamDef,
} from "./aiNodes";

const ns = (en as unknown as Record<string, Record<string, string>>).workflowNodesAi;
const t = (key: string, values?: Record<string, string | number>) => (values ? `${key}:${JSON.stringify(values)}` : key);

describe("registration", () => {
  it("registers the four node types as supported palette entries", () => {
    for (const type of AI_NODE_TYPES) {
      const def = NODE_DEFS.find((d) => d.type === type);
      expect(def, type).toBeDefined();
      expect(def!.supported).toBe(true);
      expect(nodeDef(type)).toBe(def);
    }
    expect(nodeDef("question_classifier").category).toBe("Logic");
    expect(nodeDef("variable_aggregator").category).toBe("Logic");
    expect(nodeDef("parameter_extractor").category).toBe("Intelligence");
    expect(nodeDef("document_extractor").category).toBe("Intelligence");
  });

  it("has palette label and description strings in the workflowNodesAi namespace, routed by key shape", () => {
    for (const type of AI_NODE_TYPES) {
      expect(isAiNodeDefKey(nodeLabelKey(type)), type).toBe(true);
      expect(isAiNodeDefKey(nodeDescriptionKey(type)), type).toBe(true);
      expect(ns[nodeLabelKey(type)]).toBeTruthy();
      expect(ns[nodeDescriptionKey(type)]).toBeTruthy();
    }
    expect(isAiNodeDefKey("supervisorLabel")).toBe(false);
    expect(isAiNodeDefKey("httpLabel")).toBe(false);
    expect(isAiNodeDefKey("question_classifierSummary")).toBe(false);
  });

  it("recognises AI node types", () => {
    expect(isAiNodeType("question_classifier")).toBe(true);
    expect(isAiNodeType("supervisor")).toBe(false);
    expect(isAiNodeType(undefined)).toBe(false);
  });

  it("lists exactly the expression-resolved fields core's ExpressionFields resolves", () => {
    expect(EXPRESSION_FIELDS.question_classifier).toEqual(["text"]);
    expect(EXPRESSION_FIELDS.parameter_extractor).toEqual(["text"]);
    expect(EXPRESSION_FIELDS.variable_aggregator).toEqual(["sources[]"]);
    expect(EXPRESSION_FIELDS.document_extractor).toEqual(["source"]);
  });

  it("every default config is valid except the aggregator's, which waits for its sources, and is not shared by reference", () => {
    for (const type of AI_NODE_TYPES) {
      const expected = type === "variable_aggregator" ? [{ code: "noSources" }] : [];
      expect(aiNodeProblems(type, nodeDef(type).defaultConfig), type).toEqual(expected);
    }
    expect(aiNodeDefaultConfig("question_classifier")).not.toBe(aiNodeDefaultConfig("question_classifier"));
  });
});

describe("en.json coverage", () => {
  it("has a string for every literal t() key the config form reads, plus every computed key", () => {
    const source = readFileSync(path.join(__dirname, "AiNodeConfig.tsx"), "utf8");
    const keys = new Set([...source.matchAll(/\bt\("([A-Za-z0-9_]+)"/g)].map((m) => m[1]));
    for (const type of AI_PARAM_TYPES) keys.add(`paramType_${type}`);
    for (const format of AI_DOC_FORMATS) keys.add(`format_${format}`);
    for (const code of AI_PROBLEM_CODES) keys.add(`problem_${code}`);
    expect(keys.size).toBeGreaterThan(60);
    const missing = [...keys].filter((k) => !ns[k]);
    expect(missing).toEqual([]);
  });

  it("has the subtitle strings aiNodeSummary reads", () => {
    for (const key of ["classifierSummary", "classifierNoClasses", "extractorSummary", "extractorNoParams", "aggregatorSummary", "aggregatorNoSources", "documentSummary", "documentAuto"]) {
      expect(ns[key], key).toBeTruthy();
    }
  });

  it("every problem code the validators can emit is listed (so en.json is complete)", () => {
    const emitted = new Set<string>();
    const bad: AiNodeConfig = {
      classes: [{ handle: "", description: "" }, { handle: "error", description: "" }, { handle: "a", description: "" }, { handle: "A", description: "" }, { handle: "x".repeat(70), description: "" }],
      defaultClass: "nope",
    };
    classifierProblems(bad).forEach((p) => emitted.add(p.code));
    classifierProblems({ classes: Array.from({ length: 21 }, (_, i) => ({ handle: `c${i}`, description: "" })) }).forEach((p) => emitted.add(p.code));
    parameterProblems({ parameters: [{ name: "1bad", type: "string", description: "", required: false }, { name: "a", type: "enum", description: "", required: false }, { name: "a", type: "string", description: "", required: false }] }).forEach((p) => emitted.add(p.code));
    parameterProblems({ parameters: Array.from({ length: 31 }, (_, i) => ({ name: `p${i}`, type: "string" as const, description: "", required: false })) }).forEach((p) => emitted.add(p.code));
    aggregatorProblems({ sources: [] }).forEach((p) => emitted.add(p.code));
    aggregatorProblems({ sources: Array.from({ length: 21 }, () => "x") }).forEach((p) => emitted.add(p.code));
    for (const code of emitted) expect(AI_PROBLEM_CODES as readonly string[], code).toContain(code);
    expect(emitted.size).toBe(AI_PROBLEM_CODES.length);
  });
});

describe("question_classifier", () => {
  it("builds one port per usable class and skips blank, reserved and duplicate handles", () => {
    const ports = classifierPorts({
      classes: [
        { handle: "billing", description: "invoices" },
        { handle: " ", description: "" },
        { handle: "error", description: "" },
        { handle: "Billing", description: "dup" },
        { handle: "support", description: "" },
      ],
    });
    expect(ports.map((p) => p.id)).toEqual(["billing", "support"]);
    expect(ports[0].title).toBe("billing - invoices");
    expect(ports[1].title).toBe("support");
  });

  it("clips long descriptions in the port title", () => {
    const [port] = classifierPorts({ classes: [{ handle: "a", description: "x".repeat(200) }] });
    expect(port.title.length).toBeLessThan(80);
    expect(port.title.endsWith("…")).toBe(true);
  });

  it("is tolerant of a missing or malformed classes value", () => {
    expect(classifierPorts(undefined)).toEqual([]);
    expect(classifierPorts({ classes: "nope" as unknown as undefined })).toEqual([]);
    expect(readClasses({ classes: [null, 3, { handle: 5 }] as unknown as undefined })).toEqual([{ handle: "", description: "" }]);
  });

  it("slugs handles and keeps them unique and never the reserved one", () => {
    expect(slugHandle("  Billing & Payments! ")).toBe("billing-payments");
    expect(slugHandle("---")).toBe("");
    expect(slugHandle("a".repeat(100)).length).toBe(64);
    expect(uniqueHandle("billing", ["Billing"])).toBe("billing-2");
    expect(uniqueHandle("billing", ["billing", "billing-2"])).toBe("billing-3");
    expect(uniqueHandle("error", [])).toBe("error-1");
    expect(uniqueHandle("", [])).toBe("class");
  });

  it("appends a new class with a fresh handle", () => {
    const classes = [{ handle: "class-1", description: "" }, { handle: "class-3", description: "" }];
    const next = newClass(classes);
    expect(next.handle).toBe("class-3-2");
    expect(next.description).toBe("");
  });

  it("validates like the server: two usable classes, no duplicates, no reserved handle, a real default", () => {
    expect(classifierProblems({ classes: [{ handle: "a", description: "" }, { handle: "b", description: "" }], defaultClass: "B" })).toEqual([]);
    expect(classifierProblems({ classes: [{ handle: "a", description: "" }] }).map((p) => p.code)).toContain("tooFewClasses");
    expect(classifierProblems({ classes: [{ handle: "a", description: "" }, { handle: "A", description: "" }] }).map((p) => p.code)).toContain("duplicateHandle");
    expect(classifierProblems({ classes: [{ handle: "error", description: "" }, { handle: "b", description: "" }] }).map((p) => p.code)).toContain("reservedHandle");
    expect(classifierProblems({ classes: [{ handle: "a", description: "" }, { handle: "b", description: "" }], defaultClass: "c" }).map((p) => p.code)).toContain("badDefaultClass");
  });

  it("labels an edge by its class handle, and only for a real class", () => {
    const config: AiNodeConfig = { classes: [{ handle: "billing", description: "" }, { handle: "support", description: "" }] };
    expect(classifierEdgeLabel(config, "billing")).toBe("billing");
    expect(classifierEdgeLabel(config, "nope")).toBeUndefined();
    const source = { type: "question_classifier", data: { config } };
    expect(edgeLabelFor({ sourceHandle: "support" }, source)).toBe("support");
    expect(edgeLabelFor({ sourceHandle: "error" }, source)).toBe("error");
    expect(edgeLabelFor({ sourceHandle: "missing" }, source)).toBeUndefined();
  });
});

describe("parameter_extractor", () => {
  it("normalises stored parameters", () => {
    expect(readParams({ parameters: [{ name: "a", type: "weird" as unknown as "string", description: "d", required: true }] })).toEqual([
      { name: "a", type: "string", description: "d", required: true },
    ]);
    expect(readParams(undefined)).toEqual([]);
  });

  it("names a new parameter distinctly", () => {
    const params: AiParamDef[] = [{ name: "param_2", type: "string", description: "", required: false }];
    expect(newParam(params).name).toBe("param_3");
    expect(newParam([]).name).toBe("param_1");
  });

  it("parses enum options from comma / newline text and caps them", () => {
    expect(parseOptionsText("S, M ,L,,m\nXL")).toEqual(["S", "M", "L", "XL"]);
    expect(parseOptionsText("")).toEqual([]);
    expect(parseOptionsText(Array.from({ length: 80 }, (_, i) => `o${i}`).join(",")).length).toBe(50);
    expect(optionsText({ name: "a", type: "enum", description: "", required: false, options: ["x", "y"] })).toBe("x, y");
  });

  it("drops options when the type stops being enum", () => {
    const p: AiParamDef = { name: "a", type: "enum", description: "", required: false, options: ["x"] };
    expect(withParamType(p, "number")).toEqual({ name: "a", type: "number", description: "", required: false });
    expect(withParamType(p, "enum").options).toEqual(["x"]);
  });

  it("validates names, duplicates and enum options like the server", () => {
    const ok: AiNodeConfig = { parameters: [{ name: "city", type: "string", description: "", required: true }] };
    expect(parameterProblems(ok)).toEqual([]);
    const codes = (parameters: AiParamDef[]) => parameterProblems({ parameters }).map((p) => p.code);
    expect(codes([{ name: "_hidden", type: "string", description: "", required: false }])).toContain("invalidParamName");
    expect(codes([{ name: "a", type: "string", description: "", required: false }, { name: "A", type: "string", description: "", required: false }])).toContain("duplicateParamName");
    expect(codes([{ name: "a", type: "enum", description: "", required: false }])).toContain("enumNoOptions");
    expect(codes([])).toContain("noParameters");
  });
});

describe("variable_aggregator", () => {
  it("reads sources tolerantly and requires one non-blank", () => {
    expect(readSources({ sources: ["a", 3 as unknown as string, ""] })).toEqual(["a", "", ""]);
    expect(readSources(undefined)).toEqual([]);
    expect(aggregatorProblems({ sources: ["", " "] }).map((p) => p.code)).toEqual(["noSources"]);
    expect(aggregatorProblems({ sources: ["", "{{ $node[\"A\"].x }}"] })).toEqual([]);
  });
});

describe("list helpers are immutable", () => {
  it("updateAt / setAt / removeAt / replaceAt return new arrays and leave the input alone", () => {
    const list = [{ a: 1 }, { a: 2 }];
    expect(updateAt(list, 1, { a: 9 })).toEqual([{ a: 1 }, { a: 9 }]);
    expect(setAt(list, 0, { a: 5 })).toEqual([{ a: 5 }, { a: 2 }]);
    expect(removeAt(list, 0)).toEqual([{ a: 2 }]);
    expect(replaceAt(["x", "y"], 1, "z")).toEqual(["x", "z"]);
    expect(list).toEqual([{ a: 1 }, { a: 2 }]);
  });

  it("optionalInt leaves blank unset and clamps", () => {
    expect(optionalInt("", 1, 10)).toBeUndefined();
    expect(optionalInt("abc", 1, 10)).toBeUndefined();
    expect(optionalInt("500", 1, 10)).toBe(10);
    expect(optionalInt("0", 1, 10)).toBe(1);
    expect(optionalInt("7.4", 1, 10)).toBe(7);
  });
});

describe("aiNodeSummary", () => {
  it("summarises each type, with a nudge when empty", () => {
    expect(aiNodeSummary("question_classifier", { classes: [{ handle: "a", description: "" }] }, t)).toBe('classifierSummary:{"count":1}');
    expect(aiNodeSummary("question_classifier", {}, t)).toBe("classifierNoClasses");
    expect(aiNodeSummary("parameter_extractor", { parameters: [{ name: "a", type: "string", description: "", required: false }] }, t)).toBe('extractorSummary:{"count":1}');
    expect(aiNodeSummary("parameter_extractor", undefined, t)).toBe("extractorNoParams");
    expect(aiNodeSummary("variable_aggregator", { sources: ["", "x"] }, t)).toBe('aggregatorSummary:{"count":1}');
    expect(aiNodeSummary("variable_aggregator", { sources: [] }, t)).toBe("aggregatorNoSources");
    expect(aiNodeSummary("document_extractor", { format: "pdf" }, t)).toBe('documentSummary:{"format":"PDF"}');
    expect(aiNodeSummary("document_extractor", {}, t)).toBe('documentSummary:{"format":"documentAuto"}');
    expect(aiNodeSummary("something_else", {}, t)).toBe("");
  });
});
