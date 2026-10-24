import { describe, expect, it } from "vitest";
import { renderHook, act } from "@testing-library/react";
import {
  chatStreamReducer as reduce,
  initialChatStreamState as idle,
  isBusyPhase,
  type ChatStreamEvent,
  type ChatStreamState,
} from "./chatStreamMachine";
import { useChatStream } from "./useChatStream";

function run(events: ChatStreamEvent[], from: ChatStreamState = idle): ChatStreamState {
  return events.reduce(reduce, from);
}

describe("chatStreamReducer", () => {
  it("idle -> streaming -> idle on a normal turn", () => {
    expect(run([{ type: "start" }]).phase).toBe("streaming");
    expect(run([{ type: "start" }, { type: "settle" }]).phase).toBe("idle");
  });

  it("a failure during a turn does not cut it short; the phase moves to error when it settles", () => {
    const failing = run([{ type: "start" }, { type: "fail", message: "boom" }]);
    expect(failing.phase).toBe("streaming");
    expect(failing.error).toBe("boom");
    expect(reduce(failing, { type: "settle" }).phase).toBe("error");
  });

  it("a failure that kept partial text settles as resumable", () => {
    const s = run([{ type: "start" }, { type: "fail", message: "cut off", resumable: true }, { type: "settle" }]);
    expect(s.phase).toBe("resumable");
    expect(s.resumable).toBe(true);
  });

  it("a failure while idle goes straight to error, and clearing it returns to idle", () => {
    const failed = run([{ type: "fail", message: "bad key" }]);
    expect(failed.phase).toBe("error");
    expect(reduce(failed, { type: "clear-error" })).toEqual(idle);
  });

  it("starting a new turn keeps the old error until it is cleared (the send handler clears it)", () => {
    const s = run([{ type: "fail", message: "bad key" }, { type: "start" }]);
    expect(s.phase).toBe("streaming");
    expect(s.error).toBe("bad key");
    expect(run([{ type: "clear-error" }], s).phase).toBe("streaming");
  });

  it("only a streaming turn is busy (a parked tool call ends its turn; there is no awaiting-approval phase)", () => {
    expect(isBusyPhase("streaming")).toBe(true);
    for (const phase of ["idle", "error", "resumable"] as const) expect(isBusyPhase(phase)).toBe(false);
  });
});

describe("useChatStream", () => {
  it("exposes sending/error with the shapes the playground always had", () => {
    const { result } = renderHook(() => useChatStream());
    expect(result.current.sending).toBe(false);
    act(() => result.current.setSending(true));
    expect(result.current.sending).toBe(true);
    expect(result.current.phase).toBe("streaming");
    act(() => result.current.setError("nope", { resumable: true }));
    act(() => result.current.setSending(false));
    expect(result.current.sending).toBe(false);
    expect(result.current.phase).toBe("resumable");
    expect(result.current.error).toBe("nope");
    act(() => result.current.setError(null));
    expect(result.current.phase).toBe("idle");
    expect(result.current.error).toBeNull();
  });
});
