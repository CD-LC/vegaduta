// The five turn-lifecycle step events (finish, retry, failover, continuation, approval_required) cross the SSE
// boundary with every field intact: the console reads finishReason, attempt, toModel, pass and approvalId from them.
import { describe, expect, it } from "vitest";
import { parseStepEventPayload } from "./schemas";

describe("lifecycle step events at the stream boundary", () => {
  it.each([
    ['{"type":"finish","finishReason":"length","detail":"MAX_TOKENS"}', { type: "finish", finishReason: "length", detail: "MAX_TOKENS" }],
    ['{"type":"retry","attempt":2,"maxAttempts":3,"reason":"rate_limited"}', { type: "retry", attempt: 2, maxAttempts: 3, reason: "rate_limited" }],
    ['{"type":"failover","toModel":"gpt-4o-mini","reason":"timeout"}', { type: "failover", toModel: "gpt-4o-mini", reason: "timeout" }],
    ['{"type":"continuation","pass":1,"maxPasses":2,"reason":"cut_off"}', { type: "continuation", pass: 1, maxPasses: 2, reason: "cut_off" }],
    ['{"type":"approval_required","tool":"send_email","approvalId":"c0ffee00-0000-4000-8000-000000000001"}', { type: "approval_required", tool: "send_email", approvalId: "c0ffee00-0000-4000-8000-000000000001" }],
  ])("keeps %s", (json, expected) => {
    expect(parseStepEventPayload(json)).toMatchObject(expected);
  });
});
