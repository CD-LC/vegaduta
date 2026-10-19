"use client";

import { useReducer } from "react";
import {
  INITIAL_CALL_MACHINE,
  callMachineReducer,
  deriveCallPhase,
  type CallPhase,
} from "./callMachine";

/** Owns the phase transitions; the connection status itself stays in CallPanel. */
export function useCallMachine(status: string, transcriptLines: number): {
  phase: CallPhase;
  markDialing: () => void;
  markConnected: () => void;
  markHangingUp: () => void;
  markEnded: () => void;
  markReset: () => void;
} {
  const [state, dispatch] = useReducer(callMachineReducer, INITIAL_CALL_MACHINE);
  return {
    phase: deriveCallPhase({ status, hangingUp: state.hangingUp, transcriptLines, hadCall: state.hadCall }),
    markDialing: () => dispatch({ type: "dial" }),
    markConnected: () => dispatch({ type: "connected" }),
    markHangingUp: () => dispatch({ type: "hangup" }),
    markEnded: () => dispatch({ type: "ended" }),
    markReset: () => dispatch({ type: "reset" }),
  };
}
