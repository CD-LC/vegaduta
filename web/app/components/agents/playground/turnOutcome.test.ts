import { describe, expect, it } from "vitest";
import type { ActivityStep } from "../AgentActivityTrace";
import { deriveTurnOutcome } from "./turnOutcome";

const finish = (details: Record<string, string>): ActivityStep[] => [{ type: "finish", id: 9, details }];

describe("deriveTurnOutcome", () => {
  it("is null for an ordinary reply", () => {
    expect(deriveTurnOutcome({ content: "Here you go." })).toBeNull();
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "stop" }), content: "Here you go." })).toBeNull();
  });

  it("reads a cut-off reply from the finish event, in any provider spelling", () => {
    for (const reason of ["length", "max_tokens", "MAX_TOKENS", "max-output-tokens"]) {
      expect(deriveTurnOutcome({ steps: finish({ finishReason: reason }), content: "partial" })).toMatchObject({
        kind: "truncated",
        source: "event",
      });
    }
  });

  it("reads a refusal and keeps the provider's reason readable", () => {
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "content_filter" }) })).toMatchObject({
      kind: "refused",
      reason: "content filter",
    });
  });

  it("reads an unusable tool call and an empty reply", () => {
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "malformed_function_call" }) })?.kind).toBe(
      "malformedToolCall"
    );
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "empty_reply" }) })?.kind).toBe("empty");
  });

  it("lets the latest finish event decide", () => {
    const steps: ActivityStep[] = [
      { type: "finish", id: 1, details: { finishReason: "length" } },
      { type: "finish", id: 2, details: { finishReason: "stop" } },
    ];
    expect(deriveTurnOutcome({ steps, content: "x" })).toBeNull();
  });

  it("falls back to the markers core writes into the reply text", () => {
    expect(deriveTurnOutcome({ content: "Part of an answer\n\n[reply interrupted]" })).toMatchObject({
      kind: "truncated",
      source: "text",
    });
    expect(
      deriveTurnOutcome({
        content: "Text.\n\n[This reply was cut off because the model reached its output length limit. Ask for the rest.]",
      })?.kind
    ).toBe("truncated");
    expect(
      deriveTurnOutcome({
        content: "The model provider's safety system stopped this reply (SAFETY), so there is no answer to show.",
      })
    ).toMatchObject({ kind: "refused", reason: "SAFETY", source: "text" });
  });

  it("ignores a finish event it cannot classify", () => {
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "something_new" }), content: "x" })).toBeNull();
  });

  it("lets a finish event decide alone: the reply text is not consulted when the field is there", () => {
    const marked = "Part of an answer\n\n[reply interrupted]";
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "stop" }), content: marked })).toBeNull();
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "something_new" }), content: marked })).toBeNull();
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "length" }), content: "reworded notice" })).toMatchObject({
      kind: "truncated",
      source: "event",
    });
  });

  it("reads a reply broken off after some text from the finish event", () => {
    expect(deriveTurnOutcome({ steps: finish({ finishReason: "interrupted" }), content: "part" })).toMatchObject({
      kind: "truncated",
      source: "event",
    });
  });

  it("names a refusal by the provider's own word when core sends it as detail", () => {
    expect(
      deriveTurnOutcome({ steps: finish({ finishReason: "safety", detail: "content_filter" }), content: "" })
    ).toEqual({ kind: "refused", reason: "content filter", source: "event" });
  });

  it("uses the text fallback only for a turn with no finish event, or one without a reason", () => {
    const marked = "Part of an answer [reply interrupted]";
    expect(deriveTurnOutcome({ steps: [{ type: "phase", id: 1 }], content: marked })).toMatchObject({ source: "text" });
    expect(deriveTurnOutcome({ steps: [{ type: "finish", id: 2 }], content: marked })).toMatchObject({ source: "text" });
  });
});
