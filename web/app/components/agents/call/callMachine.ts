// The call's phase state machine: setup -> dialing -> in-call -> wrap-up ->
// summary. The LiveKit connection status ("idle" | "connecting" | "connected"
// | "error") stays the single source of truth for the live connection; this
// module only derives the phase the UI should present from it, so the two can
// never disagree. wrap-up is the short window between "hang up" and the
// disconnect settling; summary is an ended call whose transcript is still
// readable (endCall deliberately keeps it).

export type CallPhase = "setup" | "dialing" | "in-call" | "wrap-up" | "summary";

export interface CallPhaseInput {
  status: string;
  /** True from the moment hang-up is requested until the room has disconnected. */
  hangingUp: boolean;
  transcriptLines: number;
  /** A completed call happened in this panel session (summary rather than setup afterwards). */
  hadCall: boolean;
}

export function deriveCallPhase({ status, hangingUp, transcriptLines, hadCall }: CallPhaseInput): CallPhase {
  if (hangingUp) return "wrap-up";
  if (status === "connecting") return "dialing";
  if (status === "connected") return "in-call";
  if (hadCall && transcriptLines > 0) return "summary";
  return "setup";
}

export interface CallMachineState {
  hangingUp: boolean;
  hadCall: boolean;
}

export type CallMachineEvent =
  | { type: "dial" }
  | { type: "connected" }
  | { type: "hangup" }
  | { type: "ended" }
  | { type: "reset" };

export const INITIAL_CALL_MACHINE: CallMachineState = { hangingUp: false, hadCall: false };

export function callMachineReducer(state: CallMachineState, event: CallMachineEvent): CallMachineState {
  switch (event.type) {
    case "dial":
      return { hangingUp: false, hadCall: false };
    case "connected":
      return { ...state, hadCall: true };
    case "hangup":
      return { ...state, hangingUp: true };
    case "ended":
      return { ...state, hangingUp: false };
    case "reset":
      return INITIAL_CALL_MACHINE;
    default:
      return state;
  }
}
