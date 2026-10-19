import { describe, expect, it } from "vitest";
import { INITIAL_CALL_MACHINE, callMachineReducer, deriveCallPhase } from "./callMachine";

describe("deriveCallPhase", () => {
  it("is setup before any call", () => {
    expect(deriveCallPhase({ status: "idle", hangingUp: false, transcriptLines: 0, hadCall: false })).toBe("setup");
  });
  it("follows the connection status while dialing and in-call", () => {
    expect(deriveCallPhase({ status: "connecting", hangingUp: false, transcriptLines: 0, hadCall: false })).toBe("dialing");
    expect(deriveCallPhase({ status: "connected", hangingUp: false, transcriptLines: 2, hadCall: true })).toBe("in-call");
  });
  it("is wrap-up while hanging up, regardless of status", () => {
    expect(deriveCallPhase({ status: "idle", hangingUp: true, transcriptLines: 2, hadCall: true })).toBe("wrap-up");
    expect(deriveCallPhase({ status: "connected", hangingUp: true, transcriptLines: 0, hadCall: true })).toBe("wrap-up");
  });
  it("is summary only for an ended call that left a transcript", () => {
    expect(deriveCallPhase({ status: "idle", hangingUp: false, transcriptLines: 2, hadCall: true })).toBe("summary");
    expect(deriveCallPhase({ status: "idle", hangingUp: false, transcriptLines: 0, hadCall: true })).toBe("setup");
  });
  it("falls back to setup after a failed dial", () => {
    expect(deriveCallPhase({ status: "error", hangingUp: false, transcriptLines: 0, hadCall: false })).toBe("setup");
  });
  it("keeps hold-to-talk transcript (no call) in setup, not summary", () => {
    expect(deriveCallPhase({ status: "idle", hangingUp: false, transcriptLines: 4, hadCall: false })).toBe("setup");
  });
});

describe("callMachineReducer", () => {
  it("walks dial -> connected -> hangup -> ended", () => {
    let s = callMachineReducer(INITIAL_CALL_MACHINE, { type: "dial" });
    expect(s).toEqual({ hangingUp: false, hadCall: false });
    s = callMachineReducer(s, { type: "connected" });
    expect(s.hadCall).toBe(true);
    s = callMachineReducer(s, { type: "hangup" });
    expect(s.hangingUp).toBe(true);
    s = callMachineReducer(s, { type: "ended" });
    expect(s).toEqual({ hangingUp: false, hadCall: true });
  });
  it("a redial clears the previous call's summary flag", () => {
    const ended = { hangingUp: false, hadCall: true };
    expect(callMachineReducer(ended, { type: "dial" }).hadCall).toBe(false);
  });
  it("reset returns the initial state", () => {
    expect(callMachineReducer({ hangingUp: true, hadCall: true }, { type: "reset" })).toEqual(INITIAL_CALL_MACHINE);
  });
});
