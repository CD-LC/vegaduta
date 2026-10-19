// Per-agent tool argument policies card - the pure half: the defensive read of the wire shape, the client-side
// pre-check that mirrors the server's caps, and the summaries shown for the stored document.

import { describe, expect, it } from "vitest";
import {
  countRules,
  DEFAULT_LIMITS,
  describeRule,
  documentToText,
  EXAMPLE_DOCUMENT,
  isTextDirty,
  normalizeToolPolicies,
  parsePolicyText,
  policySummaries,
  type ToolPolicyDocument,
} from "./toolPolicies";

const doc: ToolPolicyDocument = {
  policies: [
    { tool: "fetch_web_page", rules: [{ path: "url", urlHostIn: ["example.com"], required: true }] },
    { tool: "t", denyUnlistedArgs: true, rules: [{ path: "n", type: "integer", min: 1, max: 5 }] },
  ],
};

describe("normalizeToolPolicies", () => {
  const wire = {
    enabled: true,
    policies: { policies: [{ tool: "a", rules: [{ path: "x", type: "string" }], denyUnlistedArgs: true }] },
    limits: { maxPolicies: 10, maxRulesPerPolicy: 5, maxDocumentChars: 1000, maxPatternLength: 50, maxEnumValues: 7 },
    updatedBy: "alice",
    updatedAt: "2026-10-04T10:00:00Z",
  };

  it("reads a response", () => {
    const v = normalizeToolPolicies(wire);
    expect(v).not.toBeNull();
    expect(v!.enabled).toBe(true);
    expect(v!.document.policies).toEqual([{ tool: "a", rules: [{ path: "x", type: "string" }], denyUnlistedArgs: true }]);
    expect(v!.limits.maxPolicies).toBe(10);
    expect(v!.updatedBy).toBe("alice");
  });

  it("falls back to the default limits and tolerates nothing stored", () => {
    const v = normalizeToolPolicies({ enabled: true, policies: { policies: [] } });
    expect(v!.limits).toEqual(DEFAULT_LIMITS);
    expect(v!.document.policies).toEqual([]);
    expect(v!.updatedBy).toBeNull();
    expect(v!.updatedAt).toBeNull();
  });

  it("returns null for anything that is not a response (a missing enabled flag is not 'on')", () => {
    expect(normalizeToolPolicies(null)).toBeNull();
    expect(normalizeToolPolicies("x")).toBeNull();
    expect(normalizeToolPolicies({ policies: { policies: [] } })).toBeNull();
    expect(normalizeToolPolicies({ enabled: "yes" })).toBeNull();
  });

  it("keeps the disabled state so the card can hide", () => {
    expect(normalizeToolPolicies({ enabled: false, policies: { policies: [] } })!.enabled).toBe(false);
  });

  it("skips malformed policies and rules instead of throwing", () => {
    const v = normalizeToolPolicies({
      enabled: true,
      policies: { policies: [null, { rules: [] }, { tool: "ok", rules: [null, { nopath: 1 }, { path: "p", max: 3 }] }, 5] },
    });
    expect(v!.document.policies).toEqual([{ tool: "ok", rules: [{ path: "p", max: 3 }] }]);
  });
});

describe("documentToText / isTextDirty", () => {
  it("renders an empty document as empty text and a stored one as pretty JSON", () => {
    expect(documentToText({ policies: [] })).toBe("");
    expect(documentToText(doc)).toContain('"tool": "fetch_web_page"');
    expect(JSON.parse(documentToText(doc))).toEqual(doc);
  });

  it("ignores whitespace and key order when comparing", () => {
    const text = documentToText(doc);
    expect(isTextDirty(text, doc)).toBe(false);
    expect(isTextDirty(JSON.stringify(doc), doc)).toBe(false);
    expect(isTextDirty(text + "\n\n", doc)).toBe(false);
    expect(isTextDirty(text.replace("fetch_web_page", "other"), doc)).toBe(true);
  });

  it("treats blank vs stored and unparseable text as changes", () => {
    expect(isTextDirty("", { policies: [] })).toBe(false);
    expect(isTextDirty("  ", { policies: [] })).toBe(false);
    expect(isTextDirty("", doc)).toBe(true);
    expect(isTextDirty("{}", { policies: [] })).toBe(true);
    expect(isTextDirty("{not json", doc)).toBe(true);
  });
});

describe("parsePolicyText", () => {
  it("accepts a document and returns it as written", () => {
    const r = parsePolicyText(JSON.stringify(doc));
    expect(r.error).toBeNull();
    expect(r.document).toEqual(doc);
  });

  it("accepts the bundled example", () => {
    expect(parsePolicyText(JSON.stringify(EXAMPLE_DOCUMENT)).error).toBeNull();
  });

  it("accepts a policy with no rules array (denyUnlistedArgs alone is valid server-side)", () => {
    expect(parsePolicyText('{"policies":[{"tool":"t","denyUnlistedArgs":true}]}').error).toBeNull();
  });

  it("rejects blank text (clearing is its own action) and bad JSON", () => {
    expect(parsePolicyText("").error).toBe("empty");
    expect(parsePolicyText("   \n ").error).toBe("empty");
    expect(parsePolicyText("{nope").error).toBe("notJson");
  });

  it("rejects the wrong outer shape", () => {
    expect(parsePolicyText("[]").error).toBe("shape");
    expect(parsePolicyText("5").error).toBe("shape");
    expect(parsePolicyText("null").error).toBe("shape");
    expect(parsePolicyText("{}").error).toBe("shape");
    expect(parsePolicyText('{"policies":{}}').error).toBe("shape");
  });

  it("names the policy that is malformed", () => {
    expect(parsePolicyText('{"policies":[{"tool":"a","rules":[]},5]}')).toMatchObject({ error: "policyShape", policy: 2 });
    expect(parsePolicyText('{"policies":[{"rules":[]}]}')).toMatchObject({ error: "policyShape", policy: 1 });
    expect(parsePolicyText('{"policies":[{"tool":" ","rules":[]}]}')).toMatchObject({ error: "policyShape", policy: 1 });
    expect(parsePolicyText('{"policies":[{"tool":"a","rules":{}}]}')).toMatchObject({ error: "policyShape", policy: 1 });
  });

  it("enforces the server's size caps", () => {
    const limits = { ...DEFAULT_LIMITS, maxPolicies: 1, maxRulesPerPolicy: 1, maxDocumentChars: 100 };
    expect(parsePolicyText('{"policies":[{"tool":"a","rules":[]},{"tool":"b","rules":[]}]}', limits)).toMatchObject({
      error: "tooManyPolicies",
      limit: 1,
    });
    expect(parsePolicyText('{"policies":[{"tool":"a","rules":[{"path":"x"},{"path":"y"}]}]}', limits)).toMatchObject({
      error: "tooManyRules",
      policy: 1,
      limit: 1,
    });
    expect(parsePolicyText(JSON.stringify({ policies: [], pad: "x".repeat(200) }), limits)).toMatchObject({
      error: "tooLarge",
      limit: 100,
    });
  });
});

describe("summaries", () => {
  it("describes what each rule checks, in the order a reader expects", () => {
    expect(
      describeRule({
        path: "u",
        required: true,
        type: "string",
        enum: ["a", "b"],
        minLength: 1,
        maxLength: 10,
        pattern: "[a-z]+",
        urlHostIn: ["example.com"],
        pathUnder: ["/workspace"],
      })
    ).toEqual([
      "required",
      "type string",
      "one of a, b",
      "min length 1",
      "max length 10",
      "pattern [a-z]+",
      "host in example.com",
      "under /workspace",
    ]);
    expect(describeRule({ path: "n", min: 1, max: 5, maxItems: 3 })).toEqual(["min 1", "max 5", "max 3 items"]);
    expect(describeRule({ path: "d", forbidden: true })).toEqual(["forbidden"]);
  });

  it("zero is a real bound, not an absent one", () => {
    expect(describeRule({ path: "n", min: 0, maxLength: 0 })).toEqual(["max length 0", "min 0"]);
  });

  it("clips long lists and long values", () => {
    const rule = { path: "p", enum: ["1", "2", "3", "4", "5", "6"], pattern: "x".repeat(100) };
    const checks = describeRule(rule);
    expect(checks[0]).toBe("one of 1, 2, 3, 4 (+2)");
    expect(checks[1].length).toBeLessThan(60);
    expect(checks[1].endsWith("…")).toBe(true);
  });

  it("summarises a document per policy and counts its rules", () => {
    const s = policySummaries(doc);
    expect(s).toHaveLength(2);
    expect(s[0]).toEqual({
      tool: "fetch_web_page",
      denyUnlistedArgs: false,
      rules: [{ path: "url", checks: ["required", "host in example.com"] }],
    });
    expect(s[1].denyUnlistedArgs).toBe(true);
    expect(countRules(doc)).toBe(2);
    expect(countRules({ policies: [] })).toBe(0);
    expect(policySummaries({ policies: [] })).toEqual([]);
  });
});

describe("updatedByLabel - no raw ids in 'Last changed by'", () => {
  const ID = "63315f9b-3e72-4c1d-9a55-0123456789ab";
  it("says 'you' for the viewer's own edit", async () => {
    const { updatedByLabel } = await import("./toolPolicies");
    expect(updatedByLabel(ID, { selfId: ID, selfName: "Asha" })).toEqual({ kind: "you", name: "Asha" });
  });
  it("uses a resolved member name", async () => {
    const { updatedByLabel } = await import("./toolPolicies");
    expect(updatedByLabel(ID, { names: new Map([[ID, "Ravi K"]]) })).toEqual({ kind: "name", name: "Ravi K" });
  });
  it("never prints an unresolved UUID, but keeps a readable value like an email", async () => {
    const { updatedByLabel } = await import("./toolPolicies");
    expect(updatedByLabel(ID)).toEqual({ kind: "unknown", name: null });
    expect(updatedByLabel("ravi@example.com")).toEqual({ kind: "name", name: "ravi@example.com" });
    expect(updatedByLabel(null)).toBeNull();
    expect(updatedByLabel("  ")).toBeNull();
  });
});
