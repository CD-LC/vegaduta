import { describe, expect, it, vi } from "vitest";
import { renderHook } from "@testing-library/react";

const setAccountPhoneNumber = vi.fn();
vi.mock("../../lib/api", () => ({ api: { setAccountPhoneNumber: (p: string) => setAccountPhoneNumber(p) } }));

import { useQuickTask } from "./useQuickTask";
import type { AssistantTurn } from "./assistantTypes";

const card = (id: string, needsPhone: boolean): AssistantTurn =>
  ({
    id,
    role: "assistant",
    content: "",
    at: 0,
    task: { message: "m", agentId: null, channel: "LOG", status: "confirm", preview: null, needsPhone },
  }) as AssistantTurn;

function setup(initial: AssistantTurn[]) {
  let turns = initial;
  const turn = {
    get turns() {
      return turns;
    },
    setTurns: (fn: (l: AssistantTurn[]) => AssistantTurn[]) => {
      turns = fn(turns);
    },
    appendTurns: vi.fn(),
  };
  const { result } = renderHook(() => useQuickTask(turn as never, vi.fn()));
  return { result, read: () => turns };
}

describe("useQuickTask.savePhone", () => {
  it("saves the number and unlocks every card that was waiting for one", async () => {
    setAccountPhoneNumber.mockResolvedValue({ phoneNumber: "+14155552671" });
    const { result, read } = setup([card("a", true), card("b", true), card("c", false)]);
    await result.current.savePhone("+14155552671");
    expect(setAccountPhoneNumber).toHaveBeenCalledWith("+14155552671");
    expect(read().map((x) => x.task?.needsPhone)).toEqual([false, false, false]);
  });

  it("leaves the cards locked and rethrows when the save fails", async () => {
    setAccountPhoneNumber.mockRejectedValue(new Error("invalid"));
    const { result, read } = setup([card("a", true)]);
    await expect(result.current.savePhone("x")).rejects.toThrow("invalid");
    expect(read()[0].task?.needsPhone).toBe(true);
  });
});
