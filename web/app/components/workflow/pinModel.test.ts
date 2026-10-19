import { describe, expect, it } from "vitest";
import type { WorkflowPin } from "../../lib/api/workflowPins";
import {
  checkPinText,
  compactPinText,
  countPinnedTrace,
  formatPinSize,
  isPinnedStatus,
  payloadHasRedaction,
  payloadNeedsPinConfirm,
  pinFailureText,
  pinLimitBreach,
  pinnedTraceNodeIds,
  pinsAreMetadataOnly,
  prettyPinText,
  toPinMeta,
  type PinMap,
} from "./pinModel";

const pin = (nodeId: string, sizeChars = 10, data: string | null = "{}"): WorkflowPin => ({
  nodeId,
  source: "test",
  sourceRunId: null,
  pinnedBy: "me",
  pinnedAt: "2026-09-30T10:00:00Z",
  sizeChars,
  configHash: null,
  data,
});
const mapOf = (...pins: WorkflowPin[]): PinMap => Object.fromEntries(pins.map((p) => [p.nodeId, p]));

describe("trace helpers", () => {
  it("recognises the PINNED status case-insensitively", () => {
    expect(isPinnedStatus("PINNED")).toBe(true);
    expect(isPinnedStatus(" pinned ")).toBe(true);
    expect(isPinnedStatus("OK")).toBe(false);
    expect(isPinnedStatus(null)).toBe(false);
  });
  it("counts pinned entries and distinct pinned nodes", () => {
    const trace = [
      { nodeId: "a", status: "PINNED" },
      { nodeId: "b", status: "OK" },
      { nodeId: "a", status: "PINNED" }, // loop body visited twice
      null,
    ];
    expect(countPinnedTrace(trace)).toBe(2);
    expect(pinnedTraceNodeIds(trace)).toEqual(["a"]);
    expect(countPinnedTrace(undefined)).toBe(0);
  });
});

describe("checkPinText", () => {
  it("rejects empty text", () => {
    expect(checkPinText("   ")).toMatchObject({ ok: false, reason: "empty" });
  });
  it("accepts valid JSON and plain text", () => {
    expect(checkPinText('{"a":1}')).toMatchObject({ ok: true, json: true });
    expect(checkPinText("[1,2]")).toMatchObject({ ok: true, json: true });
    expect(checkPinText("hello there")).toMatchObject({ ok: true, json: false });
    expect(checkPinText("42")).toMatchObject({ ok: true, json: true });
  });
  it("rejects malformed JSON objects and arrays with a detail", () => {
    const r = checkPinText('{"a":');
    expect(r.ok).toBe(false);
    expect(r.reason).toBe("invalid-json");
    expect(typeof r.detail).toBe("string");
    expect(checkPinText("[1,").reason).toBe("invalid-json");
  });
  it("enforces the 256,000 character cap exactly", () => {
    expect(checkPinText("x".repeat(256_000)).ok).toBe(true);
    const over = checkPinText("x".repeat(256_001));
    expect(over.ok).toBe(false);
    expect(over.reason).toBe("too-large");
  });
});

describe("pretty / compact", () => {
  it("round-trips JSON and leaves non-JSON alone", () => {
    expect(prettyPinText('{"a":[1,2]}')).toBe('{\n  "a": [\n    1,\n    2\n  ]\n}');
    expect(compactPinText('{\n  "a": [1, 2]\n}')).toBe('{"a":[1,2]}');
    expect(prettyPinText("not json")).toBe("not json");
    expect(compactPinText("not json")).toBe("not json");
    expect(compactPinText('{"a":')).toBe('{"a":');
  });
});

describe("pinLimitBreach", () => {
  it("allows a pin under both caps", () => {
    expect(pinLimitBreach(mapOf(pin("a")), "b", 100)).toBeNull();
  });
  it("blocks the 31st pin but lets an existing node be replaced", () => {
    const pins = mapOf(...Array.from({ length: 30 }, (_, i) => pin(`n${i}`)));
    expect(pinLimitBreach(pins, "extra", 10)).toBe("count");
    expect(pinLimitBreach(pins, "n3", 10)).toBeNull();
  });
  it("blocks past 2,000,000 chars in total, counting the replaced pin's share as freed", () => {
    const pins = mapOf(pin("a", 1_900_000), pin("b", 50_000));
    expect(pinLimitBreach(pins, "c", 100_001)).toBe("total");
    expect(pinLimitBreach(pins, "c", 50_000)).toBeNull();
    expect(pinLimitBreach(pins, "a", 256_000)).toBeNull();
  });
});

describe("redaction / truncation confirmation", () => {
  it("asks when the payload is truncated or carries the redaction marker", () => {
    expect(payloadNeedsPinConfirm('{"a":1}', false)).toBe(false);
    expect(payloadNeedsPinConfirm('{"a":1}', true)).toBe(true);
    expect(payloadNeedsPinConfirm('{"token":"[redacted]"}', false)).toBe(true);
    expect(payloadNeedsPinConfirm("Authorization: Bearer [REDACTED]", null)).toBe(true);
    expect(payloadHasRedaction('{"a":1}')).toBe(false);
  });
});

describe("permissions", () => {
  it("treats a pin with withheld data as a metadata-only viewer", () => {
    expect(pinsAreMetadataOnly(mapOf(pin("a", 5, null)))).toBe(true);
    expect(pinsAreMetadataOnly(mapOf(pin("a")))).toBe(false);
    expect(pinsAreMetadataOnly({})).toBe(false);
  });
  it("is metadata-only when just one pin has its data withheld", () => {
    expect(pinsAreMetadataOnly(mapOf(pin("a"), pin("b", 5, null)))).toBe(true);
  });
});

describe("toPinMeta", () => {
  it("drops the payload and is stable per pin object", () => {
    const p = pin("a", 5, "secret-ish");
    const m = toPinMeta(p);
    expect("data" in m).toBe(false);
    expect(m.nodeId).toBe("a");
    expect(toPinMeta(p)).toBe(m);
  });
});

describe("formatPinSize", () => {
  it("formats small and large sizes", () => {
    expect(formatPinSize(12)).toBe("12 chars");
    expect(formatPinSize(4200)).toBe("4.2k chars");
    expect(formatPinSize(256_000)).toBe("256k chars");
  });
});

describe("pinFailureText", () => {
  const t = (key: string, values?: Record<string, string | number>) => `${key}:${JSON.stringify(values ?? {})}`;
  it("maps each documented 400 to its own message", () => {
    expect(pinFailureText(t, "too-large", "")).toContain('pinErrTooLarge:{"max":"256,000"}');
    expect(pinFailureText(t, "too-many", "")).toContain('pinErrTooMany:{"max":30}');
    expect(pinFailureText(t, "total-size", "")).toContain('pinErrTotalSize:{"max":"2,000,000"}');
  });
  it("surfaces the server message for an unrecognised 400 and a generic line otherwise", () => {
    expect(pinFailureText(t, "invalid", "bad thing")).toContain('"message":"bad thing"');
    expect(pinFailureText(t, "invalid", "")).toBe("pinErrGeneric:{}");
    expect(pinFailureText(t, "network", "")).toBe("pinErrNetwork:{}");
    expect(pinFailureText(t, "forbidden", "")).toBe("pinErrForbidden:{}");
    expect(pinFailureText(t, "error", "")).toBe("pinErrGeneric:{}");
  });
});
