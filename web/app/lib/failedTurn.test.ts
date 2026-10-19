import { describe, expect, it } from "vitest";
import { FAILED_REPLY_PREFIX, failedDisplayText, isCancelledError, isFailedMessage, retryableUserText } from "./failedTurn";
import { mergeServerPath, turnsFromPath } from "./chatBranching";

describe("failedTurn - which messages count as a failed reply", () => {
  it("accepts every marker core may use", () => {
    expect(isFailedMessage({ status: "failed", content: "x" })).toBe(true);
    expect(isFailedMessage({ error: true, content: "x" })).toBe(true);
    expect(isFailedMessage({ content: `${FAILED_REPLY_PREFIX}The provider said no.` })).toBe(true);
    expect(isFailedMessage({ text: `${FAILED_REPLY_PREFIX}The provider said no.` })).toBe(true);
  });

  it("does not flag ordinary text, even text that mentions failure", () => {
    expect(isFailedMessage({ content: "Your reply failed to load? Let me check." })).toBe(false);
    expect(isFailedMessage({ status: "ok", error: false, content: "hi" })).toBe(false);
    expect(isFailedMessage(null)).toBe(false);
    expect(isFailedMessage(undefined)).toBe(false);
  });

  it("strips the literal prefix for display and leaves everything else alone", () => {
    expect(failedDisplayText({ content: `${FAILED_REPLY_PREFIX}Rejected.` })).toBe("Rejected.");
    expect(failedDisplayText({ status: "failed", content: "Rejected." })).toBe("Rejected.");
    expect(failedDisplayText(null)).toBe("");
  });

  it("only offers retry for a plain text user message right before the failed reply", () => {
    const turns = [
      { role: "user", content: "Say ok." },
      { role: "assistant", content: "x" },
    ];
    expect(retryableUserText(turns, 1)).toBe("Say ok.");
    expect(retryableUserText(turns, 0)).toBeNull();
    expect(retryableUserText([{ role: "assistant", content: "x" }, turns[1]], 1)).toBeNull();
    expect(retryableUserText([{ role: "user", content: "see file", attachment: { id: 1 } }, turns[1]], 1)).toBeNull();
    expect(retryableUserText([{ role: "user", content: "see", attachments: [{}] }, turns[1]], 1)).toBeNull();
  });

  it("a cancel is not a failure", () => {
    expect(isCancelledError(Object.assign(new Error("Cancelled"), { failureClass: "aborted" }))).toBe(true);
    expect(isCancelledError(new DOMException("Aborted", "AbortError"))).toBe(true);
    expect(isCancelledError(new Error("boom"))).toBe(false);
  });
});

describe("failed replies in a history the server returns", () => {
  const path = (assistant: Record<string, unknown>) => ({
    sessionId: "s",
    activeLeafId: "m2",
    messages: [
      { id: "m1", parentId: null as string | null, role: "user" as const, content: "Say ok.", siblingIndex: 0, siblingCount: 1 },
      { id: "m2", parentId: "m1", role: "assistant" as const, content: "", siblingIndex: 0, siblingCount: 1, ...assistant },
    ],
  });

  it("rebuilds the failed turn as an error entry (prefix marker)", () => {
    const turns = turnsFromPath(path({ content: `${FAILED_REPLY_PREFIX}Rejected.` }));
    expect(turns[1]).toMatchObject({ role: "assistant", failed: true, content: "Rejected." });
    expect(turns[0].failed).toBeUndefined();
  });

  it("rebuilds it from the status / error flags too", () => {
    expect(turnsFromPath(path({ content: "Rejected.", status: "failed" }))[1]).toMatchObject({ failed: true, content: "Rejected." });
    expect(turnsFromPath(path({ content: "Rejected.", error: true }))[1]).toMatchObject({ failed: true });
  });

  it("labels a local turn the panel did not know had failed", () => {
    const merged = mergeServerPath(
      [
        { role: "user", content: "Say ok." },
        { role: "assistant", content: "" },
      ],
      path({ error: true, content: "Rejected." }),
    );
    expect(merged?.[1]).toMatchObject({ failed: true, content: "Rejected.", messageId: "m2" });
  });
});
