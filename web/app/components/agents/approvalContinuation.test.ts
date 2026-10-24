import { describe, expect, it } from "vitest";
import { continuationRequestId, isApprovalContinuationMessage, locateContinuation } from "./approvalContinuation";

const marker = (id: string, outcome = "completed") =>
  `<approval_result request="${id}" tool="send_email" outcome="${outcome}">\nThe tool call you asked for ...\n</approval_result>`;

describe("approval continuation messages", () => {
  it("recognises the message core stores, and the request it belongs to", () => {
    expect(isApprovalContinuationMessage(marker("r1"))).toBe(true);
    expect(continuationRequestId(marker("r1"))).toBe("r1");
    expect(isApprovalContinuationMessage("send an email to bob")).toBe(false);
    expect(isApprovalContinuationMessage(null)).toBe(false);
    expect(continuationRequestId("send an email to bob")).toBeNull();
    expect(continuationRequestId("<approval_result>")).toBeNull();
  });
});

describe("locateContinuation", () => {
  const original = [
    { role: "user", content: "email bob the report" },
    { role: "assistant", content: "I am waiting for approval." },
    { role: "assistant", content: "[approved action 'send_email' completed: sent]" },
  ];

  it("waits while there is no stored conversation or no continuation message", () => {
    expect(locateContinuation(null, "r1")).toEqual({ phase: "waiting" });
    expect(locateContinuation(original, "r1")).toEqual({ phase: "waiting" });
  });

  it("does not take another decision's continuation for this one", () => {
    expect(locateContinuation([...original, { role: "user", content: marker("r2") }], "r1")).toEqual({
      phase: "waiting",
    });
  });

  it("is running once the continuation message is stored and no reply follows it", () => {
    expect(locateContinuation([...original, { role: "user", content: marker("r1") }], "r1")).toEqual({
      phase: "running",
    });
  });

  it("only counts an assistant message AFTER the continuation message as its reply", () => {
    const done = [
      ...original,
      { role: "user", content: marker("r1") },
      { role: "assistant", content: "Sent. Anything else?" },
    ];
    expect(locateContinuation(done, "r1")).toEqual({ phase: "replied", content: "Sent. Anything else?", failed: false });
  });

  it("reports a broken continuation turn as a failed reply with its reason", () => {
    const broken = [
      ...original,
      { role: "user", content: marker("r1", "denied") },
      { role: "assistant", content: "The provider is down.", status: "failed" },
    ];
    expect(locateContinuation(broken, "r1")).toEqual({ phase: "replied", content: "The provider is down.", failed: true });
  });
});
