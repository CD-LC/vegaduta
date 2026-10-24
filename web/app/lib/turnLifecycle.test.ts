import { describe, expect, it } from "vitest";
import { normalizeServerStep } from "../components/agents/AgentActivityTrace";
import { finishKindOf, humanizeReason, lifecycleDetails } from "./turnLifecycle";

describe("lifecycleDetails", () => {
  it("keeps only scalar detail fields, and only for lifecycle event types", () => {
    expect(
      lifecycleDetails("retry", { attempt: 2, maxAttempts: 3, reason: "timeout", junk: "x", model: { a: 1 }, pass: NaN })
    ).toEqual({ attempt: 2, maxAttempts: 3, reason: "timeout" });
    expect(lifecycleDetails("tool_call", { reason: "x" })).toBeUndefined();
    expect(lifecycleDetails("retry", {})).toBeUndefined();
  });
});

describe("normalizeServerStep with lifecycle events", () => {
  it("attaches details to a lifecycle event", () => {
    const step = normalizeServerStep({ type: "failover", id: 4, toModel: "m2", reason: "rate_limited" }, 1);
    expect(step.details).toEqual({ toModel: "m2", reason: "rate_limited" });
    expect(step.message).toBe("rate_limited");
  });

  it("leaves every other step exactly as it was", () => {
    const step = normalizeServerStep({ type: "guardrail", id: 2, reason: "pii scrubbed" }, 1);
    expect("details" in step).toBe(false);
  });
});

describe("finishKindOf", () => {
  it("maps provider spellings and ignores unknown or missing reasons", () => {
    expect(finishKindOf("MAX_TOKENS")).toBe("truncated");
    expect(finishKindOf("end turn")).toBe("complete");
    expect(finishKindOf("safety")).toBe("refused");
    expect(finishKindOf("whatever")).toBeNull();
    expect(finishKindOf(undefined)).toBeNull();
  });
});

describe("humanizeReason", () => {
  it("turns machine reasons into words", () => {
    expect(humanizeReason("rate_limited")).toBe("rate limited");
    expect(humanizeReason(null)).toBe("");
  });
});
