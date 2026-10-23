import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { useApprovalContinuation } from "./useApprovalContinuation";

const MARKER = (id: string) => `<approval_result request="${id}" tool="t" outcome="completed">\nx\n</approval_result>`;
const BASE = [
  { role: "user", content: "do it" },
  { role: "assistant", content: "waiting" },
];

afterEach(() => cleanup());

function setup(
  script: Array<Array<{ role: string; content: string }> | null | "throw">,
  extra: Partial<Parameters<typeof useApprovalContinuation>[0]> = {}
) {
  let call = 0;
  const fetchTranscript = vi.fn(async () => {
    const step = script[Math.min(call++, script.length - 1)];
    if (step === "throw") throw new Error("boom");
    return step ? { messages: step } : null;
  });
  const onReply = vi.fn();
  const hook = renderHook(
    (props: { sessionId: string | null }) =>
      useApprovalContinuation({
        agentId: "a1",
        sessionId: props.sessionId,
        onReply,
        fetchTranscript,
        pollMs: 5,
        startWaitMs: 60,
        maxWaitMs: 200,
        ...extra,
      }),
    { initialProps: { sessionId: "s1" as string | null } }
  );
  return { ...hook, fetchTranscript, onReply };
}

describe("useApprovalContinuation", () => {
  it("holds `continuing` from the decision until the reply is stored, then delivers the reply exactly once", async () => {
    const { result, onReply, fetchTranscript } = setup([
      BASE,
      [...BASE, { role: "user", content: MARKER("r1") }],
      [...BASE, { role: "user", content: MARKER("r1") }, { role: "assistant", content: "Sent it." }],
    ]);
    act(() => result.current.watch("r1"));
    expect(result.current.continuing).toBe(true);
    await waitFor(() => expect(onReply).toHaveBeenCalledTimes(1));
    expect(onReply).toHaveBeenCalledWith({ requestId: "r1", content: "Sent it.", failed: false });
    expect(result.current.continuing).toBe(false);
    expect(result.current.problem).toBeNull();
    // Polling stops with the reply.
    const reads = fetchTranscript.mock.calls.length;
    await new Promise((r) => setTimeout(r, 40));
    expect(fetchTranscript.mock.calls.length).toBe(reads);
  });

  it("reports where the continuation is: waiting, then running once core's message is stored, with a start time", async () => {
    const running = [...BASE, { role: "user", content: MARKER("r1") }];
    const { result } = setup([BASE, BASE, running], { maxWaitMs: 60_000, startWaitMs: 60_000 });
    const before = Date.now();
    act(() => result.current.watch("r1"));
    expect(result.current.phase).toBe("waiting");
    expect(result.current.since).toBeGreaterThanOrEqual(before);
    await waitFor(() => expect(result.current.phase).toBe("running"));
    expect(result.current.continuing).toBe(true);
  });

  it("watches one request once, however often it is reported", async () => {
    const { result, onReply, fetchTranscript } = setup([
      [...BASE, { role: "user", content: MARKER("r1") }, { role: "assistant", content: "Done." }],
    ]);
    act(() => {
      result.current.watch("r1");
      result.current.watch("r1");
    });
    await waitFor(() => expect(onReply).toHaveBeenCalledTimes(1));
    act(() => result.current.watch("r1"));
    await new Promise((r) => setTimeout(r, 30));
    expect(onReply).toHaveBeenCalledTimes(1);
    expect(fetchTranscript).toHaveBeenCalledTimes(1);
  });

  it("says the task was not restarted when no continuation message ever shows up", async () => {
    const { result, onReply } = setup([BASE]);
    act(() => result.current.watch("r1"));
    await waitFor(() => expect(result.current.problem).toBe("notStarted"));
    expect(result.current.continuing).toBe(false);
    expect(onReply).not.toHaveBeenCalled();
    act(() => result.current.dismiss());
    expect(result.current.problem).toBeNull();
  });

  it("treats an unreadable conversation as 'not there yet', never as a crash", async () => {
    const { result, onReply } = setup(["throw", null, [...BASE, { role: "user", content: MARKER("r1") }, { role: "assistant", content: "ok" }]]);
    act(() => result.current.watch("r1"));
    await waitFor(() => expect(onReply).toHaveBeenCalledTimes(1));
  });

  it("gives up with a timeout when a started continuation never answers", async () => {
    const { result, onReply } = setup([[...BASE, { role: "user", content: MARKER("r1") }]]);
    act(() => result.current.watch("r1"));
    await waitFor(() => expect(result.current.problem).toBe("timedOut"), { timeout: 2000 });
    expect(onReply).not.toHaveBeenCalled();
    expect(result.current.continuing).toBe(false);
  });

  it("drops a watch when the conversation changes, so its reply never lands in another one", async () => {
    const { result, rerender, onReply, fetchTranscript } = setup([
      [...BASE, { role: "user", content: MARKER("r1") }],
    ]);
    act(() => result.current.watch("r1"));
    await waitFor(() => expect(fetchTranscript).toHaveBeenCalled());
    rerender({ sessionId: "s2" });
    expect(result.current.continuing).toBe(false);
    const reads = fetchTranscript.mock.calls.length;
    await new Promise((r) => setTimeout(r, 40));
    expect(fetchTranscript.mock.calls.length).toBe(reads);
    expect(onReply).not.toHaveBeenCalled();
  });

  it("does nothing without a conversation", () => {
    const { result, fetchTranscript } = setup([BASE]);
    const { result: none } = renderHook(() =>
      useApprovalContinuation({ agentId: null, sessionId: null, onReply: vi.fn(), fetchTranscript })
    );
    act(() => none.current.watch("r1"));
    expect(none.current.continuing).toBe(false);
    expect(fetchTranscript).not.toHaveBeenCalled();
    expect(result.current.continuing).toBe(false);
  });

  it("records how a continuation ended: completed, or failed with the reply as the reason", async () => {
    const done = setup([[...BASE, { role: "user", content: MARKER("r1") }, { role: "assistant", content: "Sent it." }]]);
    expect(done.result.current.outcome).toBeNull();
    act(() => done.result.current.watch("r1"));
    await waitFor(() => expect(done.result.current.outcome).toBe("completed"));
    act(() => done.result.current.dismiss());
    expect(done.result.current.outcome).toBeNull();

    const broke = setup([
      [...BASE, { role: "user", content: MARKER("r2") }, { role: "assistant", content: "[reply failed] Provider unavailable" }],
    ]);
    act(() => broke.result.current.watch("r2"));
    await waitFor(() => expect(broke.result.current.outcome).toBe("failed"));
    expect(broke.onReply).toHaveBeenCalledWith({ requestId: "r2", content: "Provider unavailable", failed: true });
  });

  it("starting the next watch clears the previous outcome", async () => {
    const { result } = setup([
      [
        ...BASE,
        { role: "user", content: MARKER("r1") },
        { role: "assistant", content: "Sent it." },
        { role: "user", content: MARKER("r2") },
      ],
    ]);
    act(() => result.current.watch("r1"));
    await waitFor(() => expect(result.current.outcome).toBe("completed"));
    act(() => result.current.watch("r2"));
    expect(result.current.outcome).toBeNull();
    expect(result.current.continuing).toBe(true);
  });

  it("offers retry only when the host supplies one, and retrying clears the problem", async () => {
    const without = setup([BASE]);
    expect(without.result.current.retry).toBeUndefined();

    const onRetry = vi.fn();
    const { result } = setup([BASE], { onRetry });
    expect(result.current.retry).toBeTypeOf("function");
    act(() => result.current.watch("r1"));
    await waitFor(() => expect(result.current.problem).toBe("notStarted"), { timeout: 2000 });
    act(() => result.current.retry!());
    expect(onRetry).toHaveBeenCalledTimes(1);
    expect(result.current.problem).toBeNull();
  });
});
