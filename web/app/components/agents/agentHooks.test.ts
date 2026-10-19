// Agent hooks card: the pure helpers. Locks in that the client-side validation mirrors core's HookSchema, that
// what Save sends is exactly what the form shows, that every translation key the helpers build exists in
// messages/en.json (a missing key renders its own path and is invisible to tsc), and that the preview sentence
// reads as plain English.

import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import {
  ACTIONS_AT,
  BUILTIN_PATTERNS,
  HOOK_POINTS,
  MAX_HOOKS,
  SENSITIVITIES,
  TRUST_TIERS,
  describeDraft,
  describeWire,
  draftFromWire,
  emptyDraft,
  hasErrors,
  hasMatcher,
  hooksPayload,
  nextFreeId,
  normalizeAgentHooks,
  slugifyId,
  validateAll,
  validateDraft,
  wireFromDraft,
  type HookDraft,
  type HookPoint,
  type HookWire,
  type Translate,
} from "./agentHooks";

const catalog = (en as Record<string, unknown>).agentHooks as Record<string, unknown>;

function lookup(key: string): unknown {
  return key.split(".").reduce<unknown>((acc, k) => (acc && typeof acc === "object" ? (acc as Record<string, unknown>)[k] : undefined), catalog);
}

/** The real English catalog, throwing on a missing key so a typo fails loudly here. */
const t: Translate = (key, values) => {
  const raw = lookup(key);
  if (typeof raw !== "string") throw new Error(`missing agentHooks key: ${key}`);
  return raw.replace(/\{(\w+)\}/g, (m: string, k: string) => (values && k in values ? String(values[k]) : m));
};

function draft(point: HookPoint, patch: Partial<HookDraft> = {}): HookDraft {
  return { ...emptyDraft(point), id: "a-hook", ...patch };
}

describe("translation keys", () => {
  it("has the agentHooks namespace", () => {
    expect(catalog).toBeTypeOf("object");
  });

  it("has a label for every point, action, tier, sensitivity and pattern the editor offers", () => {
    for (const p of HOOK_POINTS) {
      expect(typeof lookup(`when.${p}`)).toBe("string");
      expect(typeof lookup(`do.block.${p}`)).toBe("string");
      expect(typeof lookup(`do.redact.${p}`)).toBe("string");
      expect(typeof lookup(`do.appendContext.${p}`)).toBe("string");
      for (const a of ACTIONS_AT[p]) expect(typeof lookup(`actionLabel.${a}`)).toBe("string");
    }
    for (const v of TRUST_TIERS) expect(typeof lookup(`trustLabel.${v}`)).toBe("string");
    for (const v of SENSITIVITIES) expect(typeof lookup(`sensitivityLabel.${v}`)).toBe("string");
    for (const v of BUILTIN_PATTERNS) expect(typeof lookup(`patternLabel.${v}`)).toBe("string");
    expect(typeof lookup("failModeLabel.open")).toBe("string");
    expect(typeof lookup("failModeLabel.closed")).toBe("string");
  });

  it("has every validation message the validator can return", () => {
    const keys = [
      "idRequired", "idFormat", "idDuplicate", "actionNotAllowed", "toolGlobFormat", "reasonTooLong", "labelTooLong",
      "noteRequired", "noteTooLong", "limitNeedsNumber", "limitNeedsPerMinute", "limitRange", "patternsRequired",
      "regexTooMany", "regexTooLong", "regexInvalid", "regexEmpty", "replacementTooLong", "urlRequired", "urlHttps",
      "timeoutRange",
    ];
    for (const k of keys) expect(typeof lookup(`error.${k}`)).toBe("string");
  });

  it("every error key validateDraft can produce is one of those messages", () => {
    const produced = new Set<string>();
    const probes: HookDraft[] = [
      draft("BEFORE_TOOL", { id: "" }),
      draft("BEFORE_TOOL", { id: "Bad Id" }),
      draft("AFTER_MODEL", { action: "limit" }),
      draft("BEFORE_TOOL", { tool: "has space" }),
      draft("BEFORE_TOOL", { action: "block", reason: "r".repeat(301) }),
      draft("BEFORE_TOOL", { action: "log", label: "l".repeat(101) }),
      draft("AFTER_TOOL", { action: "append_context", note: "" }),
      draft("AFTER_TOOL", { action: "append_context", note: "n".repeat(501) }),
      draft("BEFORE_TOOL", { action: "limit" }),
      draft("BEFORE_MODEL", { action: "limit" }),
      draft("BEFORE_TOOL", { action: "limit", maxPerRun: "0" }),
      draft("BEFORE_TOOL", { action: "redact" }),
      draft("BEFORE_TOOL", { action: "redact", patterns: ["email"], regex: "a\nb\nc\nd\ne\nf" }),
      draft("BEFORE_TOOL", { action: "redact", regex: "x".repeat(201) }),
      draft("BEFORE_TOOL", { action: "redact", regex: "(" }),
      draft("BEFORE_TOOL", { action: "redact", regex: "a*" }),
      draft("BEFORE_TOOL", { action: "redact", patterns: ["email"], replacement: "r".repeat(41) }),
      draft("BEFORE_TOOL", { action: "webhook", url: "" }),
      draft("BEFORE_TOOL", { action: "webhook", url: "http://x.example.com" }),
      draft("BEFORE_TOOL", { action: "webhook", url: "https://x.example.com/a", timeoutMs: "50" }),
    ];
    for (const d of probes) for (const key of Object.values(validateDraft(d, []))) produced.add(key as string);
    produced.add("idDuplicate");
    for (const k of produced) expect(typeof lookup(`error.${k}`), k).toBe("string");
  });
});

describe("action rules mirror the server", () => {
  it("require_approval only exists before a tool, and the informational points only log or notify", () => {
    for (const p of HOOK_POINTS) {
      expect(ACTIONS_AT[p].includes("require_approval")).toBe(p === "BEFORE_TOOL");
    }
    expect(ACTIONS_AT.ON_ERROR).toEqual(["webhook", "log"]);
    expect(ACTIONS_AT.ON_RUN_END).toEqual(["webhook", "log"]);
  });

  it("redact is never offered before the model, and limit never after it", () => {
    expect(ACTIONS_AT.BEFORE_MODEL.includes("redact")).toBe(false);
    expect(ACTIONS_AT.AFTER_MODEL.includes("limit")).toBe(false);
    expect(ACTIONS_AT.AFTER_TOOL.includes("limit")).toBe(false);
  });

  it("only the tool points and ON_ERROR carry a matcher", () => {
    expect(HOOK_POINTS.filter(hasMatcher)).toEqual(["BEFORE_TOOL", "AFTER_TOOL", "ON_ERROR"]);
  });
});

describe("validateDraft", () => {
  it("accepts a plain block hook", () => {
    expect(validateDraft(draft("BEFORE_TOOL", { id: "no-delete", tool: "delete_*", action: "block" }), [])).toEqual({});
  });

  it("checks the name: required, shape, unique", () => {
    expect(validateDraft(draft("BEFORE_TOOL", { id: " " }), []).id).toBe("idRequired");
    expect(validateDraft(draft("BEFORE_TOOL", { id: "Bad Id" }), []).id).toBe("idFormat");
    expect(validateDraft(draft("BEFORE_TOOL", { id: "-x" }), []).id).toBe("idFormat");
    expect(validateDraft(draft("BEFORE_TOOL", { id: "a".repeat(41) }), []).id).toBe("idFormat");
    expect(validateDraft(draft("BEFORE_TOOL", { id: "a".repeat(40) }), []).id).toBeUndefined();
    expect(validateDraft(draft("BEFORE_TOOL", { id: "dup" }), ["dup"]).id).toBe("idDuplicate");
  });

  it("rejects an action that makes no sense at its point", () => {
    expect(validateDraft(draft("AFTER_MODEL", { action: "limit", maxPerMinute: "5" }), []).action).toBe("actionNotAllowed");
    expect(validateDraft(draft("ON_ERROR", { action: "block" }), []).action).toBe("actionNotAllowed");
  });

  it("checks the tool glob only where a matcher exists", () => {
    expect(validateDraft(draft("BEFORE_TOOL", { tool: "has space" }), []).tool).toBe("toolGlobFormat");
    expect(validateDraft(draft("BEFORE_TOOL", { tool: "mcp__github__*" }), []).tool).toBeUndefined();
    expect(validateDraft(draft("BEFORE_MODEL", { action: "log", tool: "has space" }), []).tool).toBeUndefined();
  });

  it("requires a note for append_context and caps it", () => {
    expect(validateDraft(draft("AFTER_TOOL", { action: "append_context", note: "  " }), []).note).toBe("noteRequired");
    expect(validateDraft(draft("AFTER_TOOL", { action: "append_context", note: "n".repeat(501) }), []).note).toBe("noteTooLong");
    expect(hasErrors(validateDraft(draft("AFTER_TOOL", { action: "append_context", note: "Verify first" }), []))).toBe(false);
  });

  it("limit needs a sensible number, and a model limit is per minute only", () => {
    expect(validateDraft(draft("BEFORE_TOOL", { action: "limit" }), []).limit).toBe("limitNeedsNumber");
    expect(validateDraft(draft("BEFORE_MODEL", { action: "limit" }), []).limit).toBe("limitNeedsPerMinute");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "limit", maxPerRun: "0" }), []).limit).toBe("limitRange");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "limit", maxPerRun: "1001" }), []).limit).toBe("limitRange");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "limit", maxPerRun: "abc" }), []).limit).toBe("limitRange");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "limit", maxPerMinute: "10001" }), []).limit).toBe("limitRange");
    expect(hasErrors(validateDraft(draft("BEFORE_TOOL", { action: "limit", maxPerRun: "3", maxPerMinute: "30" }), []))).toBe(false);
    expect(hasErrors(validateDraft(draft("BEFORE_TOOL", { action: "limit", maxPerMinute: "30" }), []))).toBe(false);
    // The per-run box is hidden at the model point, so a stale value there must not block the save.
    expect(hasErrors(validateDraft(draft("BEFORE_MODEL", { action: "limit", maxPerMinute: "5", maxPerRun: "zzz" }), []))).toBe(false);
  });

  it("redact needs a pattern, and every regex of your own must be valid, non-empty-matching and few", () => {
    expect(validateDraft(draft("BEFORE_TOOL", { action: "redact" }), []).patterns).toBe("patternsRequired");
    expect(hasErrors(validateDraft(draft("BEFORE_TOOL", { action: "redact", patterns: ["email"] }), []))).toBe(false);
    expect(hasErrors(validateDraft(draft("BEFORE_TOOL", { action: "redact", regex: "secret-[0-9]+" }), []))).toBe(false);
    expect(validateDraft(draft("BEFORE_TOOL", { action: "redact", regex: "(" }), []).regex).toBe("regexInvalid");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "redact", regex: "a*" }), []).regex).toBe("regexEmpty");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "redact", regex: "x".repeat(201) }), []).regex).toBe("regexTooLong");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "redact", regex: "a\nb\nc\nd\ne\nf" }), []).regex).toBe("regexTooMany");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "redact", patterns: ["email"], replacement: "r".repeat(41) }), []).replacement)
      .toBe("replacementTooLong");
  });

  it("a webhook needs an https URL and a sane timeout", () => {
    expect(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "" }), []).url).toBe("urlRequired");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "http://hooks.example.com/x" }), []).url).toBe("urlHttps");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "https://user@hooks.example.com/x" }), []).url).toBe("urlHttps");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "https://hooks example.com/x" }), []).url).toBe("urlHttps");
    expect(hasErrors(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "https://hooks.example.com/x?y=1" }), []))).toBe(false);
    expect(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "https://hooks.example.com/x", timeoutMs: "50" }), []).timeoutMs)
      .toBe("timeoutRange");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "https://hooks.example.com/x", timeoutMs: "5001" }), []).timeoutMs)
      .toBe("timeoutRange");
    expect(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "https://hooks.example.com/x", timeoutMs: "x" }), []).timeoutMs)
      .toBe("timeoutRange");
    expect(hasErrors(validateDraft(draft("BEFORE_TOOL", { action: "webhook", url: "https://hooks.example.com/x", timeoutMs: "2000" }), [])))
      .toBe(false);
  });

  it("validateAll flags duplicates on both hooks and too many hooks", () => {
    const result = validateAll([draft("BEFORE_TOOL", { id: "same" }), draft("AFTER_TOOL", { id: "same" })]);
    expect(result.errors[0].id).toBe("idDuplicate");
    expect(result.errors[1].id).toBe("idDuplicate");
    expect(result.tooMany).toBe(false);

    const many = Array.from({ length: MAX_HOOKS + 1 }, (_, i) => draft("ON_RUN_END", { id: `h${i}`, action: "log" }));
    expect(validateAll(many).tooMany).toBe(true);
    expect(validateAll(many.slice(0, MAX_HOOKS)).tooMany).toBe(false);
  });
});

describe("wireFromDraft", () => {
  it("sends only what the chosen action uses", () => {
    const wire = wireFromDraft(
      draft("BEFORE_TOOL", { id: " no-delete ", tool: " delete_* ", action: "block", reason: " Not allowed ", note: "stale", url: "https://stale.example.com" })
    );

    expect(wire).toEqual({
      id: "no-delete",
      enabled: true,
      point: "BEFORE_TOOL",
      match: { tool: "delete_*" },
      action: "block",
      params: { reason: "Not allowed" },
    });
  });

  it("drops the matcher at the model and run points even if the form still holds one", () => {
    const wire = wireFromDraft(draft("BEFORE_MODEL", { action: "log", tool: "x", sensitivity: "EGRESS" }));

    expect(wire.match).toBeUndefined();
    expect(wire.params).toEqual({});
  });

  it("builds every matcher field", () => {
    const wire = wireFromDraft(draft("AFTER_TOOL", { action: "log", tool: "a_*", trust: "UNTRUSTED_EXTERNAL", sensitivity: "EGRESS", irreversible: "false" }));

    expect(wire.match).toEqual({ tool: "a_*", trust: "UNTRUSTED_EXTERNAL", sensitivity: "EGRESS", irreversible: false });
  });

  it("limit sends numbers, and no per-run value at the model point", () => {
    expect(wireFromDraft(draft("BEFORE_TOOL", { action: "limit", maxPerRun: "3", maxPerMinute: "30", message: "Slow down" })).params)
      .toEqual({ maxPerRun: 3, maxPerMinute: 30, message: "Slow down" });
    expect(wireFromDraft(draft("BEFORE_MODEL", { action: "limit", maxPerRun: "9", maxPerMinute: "5" })).params).toEqual({ maxPerMinute: 5 });
  });

  it("redact sends the chosen patterns, one regex per non-blank line, and the replacement", () => {
    const wire = wireFromDraft(draft("AFTER_MODEL", { action: "redact", patterns: ["email", "pan"], regex: " secret-\\d+ \n\n token=\\w+", replacement: " [X] " }));

    expect(wire.params).toEqual({ patterns: ["email", "pan"], regex: ["secret-\\d+", "token=\\w+"], replacement: "[X]" });
  });

  it("webhook puts failMode and timeout on the hook, not in params", () => {
    const wire = wireFromDraft(draft("BEFORE_TOOL", { action: "webhook", url: " https://hooks.example.com/check ", includeData: true, failMode: "open", timeoutMs: "1500" }));

    expect(wire.params).toEqual({ url: "https://hooks.example.com/check", includeData: true });
    expect(wire.failMode).toBe("open");
    expect(wire.timeoutMs).toBe(1500);
    const defaults = wireFromDraft(draft("AFTER_TOOL", { action: "webhook", url: "https://hooks.example.com/check" }));
    expect(defaults.failMode).toBeUndefined();
    expect(defaults.timeoutMs).toBeUndefined();
  });

  it("keeps a disabled hook disabled", () => {
    expect(wireFromDraft(draft("ON_RUN_END", { action: "log", enabled: false })).enabled).toBe(false);
  });

  it("round-trips through the draft without losing anything", () => {
    const hooks: HookWire[] = [
      { id: "a", enabled: true, point: "BEFORE_TOOL", match: { tool: "delete_*", sensitivity: "EGRESS", irreversible: true }, action: "block", params: { reason: "No" } },
      { id: "b", enabled: false, point: "AFTER_TOOL", action: "redact", params: { patterns: ["email"], regex: ["x+"], replacement: "[X]" } },
      { id: "c", enabled: true, point: "BEFORE_MODEL", action: "limit", params: { maxPerMinute: 5, message: "Later" } },
      { id: "d", enabled: true, point: "AFTER_TOOL", action: "webhook", params: { url: "https://hooks.example.com/d", includeData: true }, failMode: "closed", timeoutMs: 900 },
      { id: "e", enabled: true, point: "ON_RUN_END", action: "log", params: { label: "done" } },
      { id: "f", enabled: true, point: "AFTER_TOOL", action: "append_context", params: { note: "Careful" } },
    ];

    expect(hooks.map((h) => wireFromDraft(draftFromWire(h)))).toEqual(hooks);
  });

  it("hooksPayload wraps the list under hooks", () => {
    expect(hooksPayload([draft("ON_RUN_END", { action: "log" })]).hooks).toHaveLength(1);
    expect(hooksPayload([])).toEqual({ hooks: [] });
  });
});

describe("normalizeAgentHooks", () => {
  it("is null for anything that is not a hooks response, so the card hides", () => {
    expect(normalizeAgentHooks(null)).toBeNull();
    expect(normalizeAgentHooks("x")).toBeNull();
    expect(normalizeAgentHooks({})).toBeNull();
    expect(normalizeAgentHooks({ enabled: true })).toBeNull();
    expect(normalizeAgentHooks({ hooks: [] })).toBeNull();
  });

  it("reads the view and drops malformed hooks instead of failing", () => {
    const view = normalizeAgentHooks({
      enabled: false,
      hooks: [{ id: "a", point: "BEFORE_TOOL", action: "block" }, { id: "bad", point: "NOPE", action: "block" }, null, "x"],
      inherited: [{ id: "w", point: "ON_RUN_END", action: "log", enabled: false }],
      webhookHosts: ["hooks.example.com", 7],
      updatedAt: "2026-10-04T10:00:00Z",
    });

    expect(view).not.toBeNull();
    expect(view!.enabled).toBe(false);
    expect(view!.hooks.map((h) => h.id)).toEqual(["a"]);
    expect(view!.inherited).toHaveLength(1);
    expect(view!.inherited[0].enabled).toBe(false);
    expect(view!.webhookHosts).toEqual(["hooks.example.com"]);
    expect(view!.updatedAt).toBe("2026-10-04T10:00:00Z");
  });
});

describe("describeDraft - the preview sentence", () => {
  it("reads a block hook with a matcher and a reason", () => {
    expect(describeDraft(draft("BEFORE_TOOL", { tool: "delete_*", action: "block", reason: "Deletes are not allowed" }), t)).toBe(
      "Before a tool runs, for tools named delete_*: refuse the call (reason: Deletes are not allowed)."
    );
  });

  it("reads an approval hook that matches on sensitivity", () => {
    expect(describeDraft(draft("BEFORE_TOOL", { sensitivity: "EGRESS", action: "require_approval" }), t)).toBe(
      "Before a tool runs, for outbound tools: ask a person to approve it first."
    );
  });

  it("reads a redaction with built-in and custom patterns", () => {
    expect(describeDraft(draft("AFTER_TOOL", { action: "redact", patterns: ["email", "phone"], regex: "foo" }), t)).toBe(
      "After a tool returns, for any tool: redact email addresses, phone numbers, your own patterns (1) from the result."
    );
  });

  it("reads limits for tools and for the model", () => {
    expect(describeDraft(draft("BEFORE_MODEL", { action: "limit", maxPerMinute: "5" }), t)).toBe(
      "Before the model answers: allow at most 5 model calls per minute."
    );
    expect(describeDraft(draft("BEFORE_TOOL", { tool: "search_*", action: "limit", maxPerRun: "3", maxPerMinute: "30" }), t)).toBe(
      "Before a tool runs, for tools named search_*: allow at most 3 calls per run and 30 per minute."
    );
    expect(describeDraft(draft("BEFORE_TOOL", { action: "limit", maxPerRun: "3" }), t)).toBe(
      "Before a tool runs, for any tool: allow at most 3 calls per run."
    );
  });

  it("reads a webhook with its host, fail mode and timeout, and a notify-only one", () => {
    expect(describeDraft(draft("BEFORE_TOOL", { tool: "pay_*", action: "webhook", url: "https://hooks.example.com/check" }), t)).toBe(
      "Before a tool runs, for tools named pay_*: ask hooks.example.com to allow or deny (fails closed after 2000 ms)."
    );
    expect(describeDraft(draft("AFTER_TOOL", { action: "webhook", url: "https://hooks.example.com/check", failMode: "closed", timeoutMs: "900" }), t)).toBe(
      "After a tool returns, for any tool: ask hooks.example.com to allow or deny (fails closed after 900 ms)."
    );
    expect(describeDraft(draft("ON_RUN_END", { action: "webhook", url: "https://hooks.example.com/check" }), t)).toBe(
      "When a run ends: notify hooks.example.com."
    );
  });

  it("reads log and note hooks", () => {
    expect(describeDraft(draft("ON_ERROR", { action: "log" }), t)).toBe(
      "When something fails, for any tool or run: record it in the audit trail."
    );
    expect(describeDraft(draft("AFTER_TOOL", { action: "append_context", note: "Verify first" }), t)).toBe(
      'After a tool returns, for any tool: append this note to the result: "Verify first".'
    );
    expect(describeDraft(draft("BEFORE_MODEL", { action: "append_context", note: "Be brief" }), t)).toBe(
      'Before the model answers: give the model this note: "Be brief".'
    );
  });

  it("reads a hook straight from the wire", () => {
    expect(describeWire({ id: "x", point: "AFTER_MODEL", action: "block", params: { reason: "Not for release" } }, t)).toBe(
      "After the model answers: replace the reply with a refusal (reason: Not for release)."
    );
  });

  it("every point and action the editor offers can be described without a missing key", () => {
    for (const p of HOOK_POINTS) {
      for (const a of ACTIONS_AT[p]) {
        const d = draft(p, { action: a, note: "n", url: "https://hooks.example.com/x", patterns: ["email"], maxPerMinute: "5", maxPerRun: "2" });
        expect(() => describeDraft(d, t), `${p}/${a}`).not.toThrow();
      }
    }
  });
});

describe("ids", () => {
  it("slugifies free text", () => {
    expect(slugifyId("  Block Deletes!  ")).toBe("block-deletes");
    expect(slugifyId("a".repeat(60)).length).toBe(40);
    expect(slugifyId("***")).toBe("");
  });

  it("picks a free id", () => {
    expect(nextFreeId("block", [])).toBe("block");
    expect(nextFreeId("block", ["block"])).toBe("block-2");
    expect(nextFreeId("block", ["block", "block-2"])).toBe("block-3");
    expect(nextFreeId("***", [])).toBe("hook");
  });
});
