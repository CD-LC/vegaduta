import { describe, expect, it } from "vitest";
import {
  RETRY_DEFAULT,
  emptyFormDef,
  formIssues,
  formUrl,
  isFormTrigger,
  isHttpsUrl,
  isIrreversibleNode,
  nextFieldKey,
  normalizeRetry,
  parseOnError,
  parseOptions,
  parseWaitMode,
  parseWaitUnit,
  readFormDef,
  retryFieldPatch,
  retryPatch,
  supportsErrorStrategy,
  waitSummary,
} from "./workflowControl";
import { EXPRESSION_FIELDS, NODE_DEFS } from "./nodeDefs";

describe("error strategy", () => {
  it("reads onError tolerantly, defaulting to fail", () => {
    expect(parseOnError(undefined)).toBe("fail");
    expect(parseOnError("Continue")).toBe("continue");
    expect(parseOnError(" branch ")).toBe("branch");
    expect(parseOnError("explode")).toBe("fail");
  });

  it("normalises and clamps the retry block like the server", () => {
    expect(normalizeRetry(undefined)).toBeNull();
    expect(normalizeRetry("x")).toBeNull();
    expect(normalizeRetry({})).toEqual(RETRY_DEFAULT);
    expect(normalizeRetry({ maxAttempts: 99, backoffMs: 999999, backoff: "Exponential", retryOn: "transient" })).toEqual({
      maxAttempts: 5,
      backoffMs: 60000,
      backoff: "exponential",
      retryOn: "transient",
    });
    expect(normalizeRetry({ maxAttempts: 0, backoffMs: -4 })).toMatchObject({ maxAttempts: 1, backoffMs: 0 });
  });

  it("toggles retry on with defaults and off by removing the key", () => {
    expect(retryPatch(true, undefined).retry).toEqual(RETRY_DEFAULT);
    expect(retryPatch(true, { maxAttempts: 2 }).retry?.maxAttempts).toBe(2);
    expect(retryPatch(false, { maxAttempts: 2 }).retry).toBeUndefined();
  });

  it("clamps a single edited retry field", () => {
    expect(retryFieldPatch({ maxAttempts: 2 }, { maxAttempts: 50 }).retry.maxAttempts).toBe(5);
    expect(retryFieldPatch(undefined, { backoff: "exponential" }).retry).toMatchObject({ backoff: "exponential", maxAttempts: 3 });
  });

  it("flags the node types the server never retries without an opt-in", () => {
    for (const type of ["bulk_email", "tool", "device_control", "crm_lead", "media", "output", "workflow"]) {
      expect(isIrreversibleNode(type, {})).toBe(true);
    }
    expect(isIrreversibleNode("http", {})).toBe(false);
    expect(isIrreversibleNode("http", { method: "get" })).toBe(false);
    expect(isIrreversibleNode("http", { method: "POST" })).toBe(true);
    expect(isIrreversibleNode("agent", {})).toBe(false);
  });

  it("offers the editor on executing nodes only", () => {
    expect(supportsErrorStrategy("agent")).toBe(true);
    expect(supportsErrorStrategy("trigger")).toBe(false);
    expect(supportsErrorStrategy("wait")).toBe(false);
    for (const t of ["loop", "parallel", "fan_out", "sub_flow", "approval", "human_input"]) {
      expect(supportsErrorStrategy(t)).toBe(false);
    }
    expect(supportsErrorStrategy("http")).toBe(true);
    expect(supportsErrorStrategy(undefined)).toBe(false);
  });
});

describe("wait node", () => {
  it("infers the mode when absent", () => {
    expect(parseWaitMode({})).toBe("duration");
    expect(parseWaitMode({ until: "2026-10-05T09:00:00Z" })).toBe("until");
    expect(parseWaitMode({ mode: "webhook" })).toBe("webhook");
    expect(parseWaitMode({ mode: "nonsense", until: "x" })).toBe("until");
  });

  it("falls back to a sane unit", () => {
    expect(parseWaitUnit("Hours")).toBe("hours");
    expect(parseWaitUnit("fortnights")).toBe("minutes");
    expect(parseWaitUnit(undefined, "days")).toBe("days");
  });

  it("summarises the config for the canvas subtitle", () => {
    expect(waitSummary({ mode: "duration", duration: "2", unit: "hours" })).toEqual({ kind: "duration", duration: "2 hours" });
    expect(waitSummary({ duration: "90s" })).toEqual({ kind: "duration", duration: "90s" });
    expect(waitSummary({ mode: "until", until: "2026-10-05T09:00:00Z" })).toEqual({ kind: "until", time: "2026-10-05T09:00:00Z" });
    expect(waitSummary({ mode: "webhook" })).toEqual({ kind: "webhook" });
    expect(waitSummary({ mode: "duration" })).toEqual({ kind: "unset" });
    expect(waitSummary(undefined)).toEqual({ kind: "unset" });
  });

  it("is a registered, executable node whose expression fields mirror core", () => {
    const def = NODE_DEFS.find((d) => d.type === "wait");
    expect(def?.supported).toBe(true);
    expect(def?.defaultConfig).toMatchObject({ mode: "duration" });
    // core ExpressionFields registers exactly these for "wait" (ExpressionFieldsWebParityTest compares the lists).
    expect(EXPRESSION_FIELDS.wait).toEqual(["until", "duration"]);
  });
});

describe("hosted form", () => {
  it("recognises a form trigger", () => {
    expect(isFormTrigger({ triggerType: "form" })).toBe(true);
    expect(isFormTrigger({ triggerType: " FORM " })).toBe(true);
    expect(isFormTrigger({ triggerType: "webhook" })).toBe(false);
    expect(isFormTrigger(undefined)).toBe(false);
  });

  it("reads stored config tolerantly", () => {
    const def = readFormDef({
      title: "T",
      fields: [
        { key: "a", type: "textarea", required: "true" },
        { key: "b", type: "weird" },
        null,
        { key: "c", type: "select", options: ["x", 1] },
      ],
    });
    expect(def.title).toBe("T");
    expect(def.fields.map((f) => f.type)).toEqual(["long_text", "text", "select"]);
    expect(def.fields[0].required).toBe(true);
    expect(def.fields[2].options).toEqual(["x", "1"]);
    expect(readFormDef(undefined).fields).toEqual([]);
  });

  it("starts with one usable field", () => {
    const def = emptyFormDef();
    expect(def.fields).toHaveLength(1);
    expect(formIssues(def).keys).toEqual({});
  });

  it("flags invalid and duplicate keys, bad redirects and too many fields", () => {
    const def = emptyFormDef();
    def.fields = [
      { key: "ok", label: "", type: "text", required: false },
      { key: "1bad", label: "", type: "text", required: false },
      { key: "ok", label: "", type: "text", required: false },
    ];
    def.redirectUrl = "http://example.com";
    const issues = formIssues(def);
    expect(issues.keys).toEqual({ 1: "invalid", 2: "duplicate" });
    expect(issues.redirect).toBe(true);
    expect(formIssues({ ...def, redirectUrl: "https://example.com/done" }).redirect).toBe(false);
    expect(formIssues({ ...def, fields: Array.from({ length: 41 }, (_, i) => ({ key: `f${i}`, label: "", type: "text" as const, required: false })) }).tooManyFields).toBe(true);
  });

  it("picks the next free field key", () => {
    expect(nextFieldKey([])).toBe("field1");
    expect(nextFieldKey([{ key: "field1", label: "", type: "text", required: false }])).toBe("field2");
  });

  it("parses the options textarea one per line", () => {
    expect(parseOptions("a\n b \r\n\nA\na")).toEqual(["a", "b", "A"]);
    expect(parseOptions("")).toEqual([]);
  });

  it("allows only absolute https redirects", () => {
    expect(isHttpsUrl("https://example.com")).toBe(true);
    for (const bad of ["", "http://example.com", "javascript:alert(1)", "https://u:p@example.com", "/x"]) {
      expect(isHttpsUrl(bad)).toBe(false);
    }
  });

  it("builds the public form link from the page origin and the webhook token, never a hardcoded host", () => {
    expect(formUrl("https://app.vegaduta.ai", "tok123")).toBe("https://app.vegaduta.ai/forms/tok123");
    expect(formUrl("https://app.vegaduta.ai/", "a b")).toBe("https://app.vegaduta.ai/forms/a%20b");
    expect(formUrl(undefined, "tok")).toBeNull();
    expect(formUrl("https://x", null)).toBeNull();
  });
});
