"use client";

import { useCallback, useReducer } from "react";
import { chatStreamReducer, initialChatStreamState, isBusyPhase, type ChatStreamPhase } from "./chatStreamMachine";

export interface ChatStream {
  phase: ChatStreamPhase;
  /** A turn is in flight (streaming or waiting on a tool approval). */
  sending: boolean;
  error: string | null;
  /** The failure kept partial reply text, so continuing or retrying makes sense. */
  resumable: boolean;
  setSending: (busy: boolean) => void;
  /** null clears the error; `resumable` marks a failure that kept partial text. */
  setError: (message: string | null, opts?: { resumable?: boolean }) => void;
  awaitApproval: () => void;
  approvalResolved: () => void;
}

/** The chat stream state machine as a hook; `sending`/`error` keep the shapes the playground always had. */
export function useChatStream(): ChatStream {
  const [state, dispatch] = useReducer(chatStreamReducer, initialChatStreamState);
  const setSending = useCallback((busy: boolean) => dispatch({ type: busy ? "start" : "settle" }), []);
  const setError = useCallback(
    (message: string | null, opts?: { resumable?: boolean }) =>
      dispatch(message ? { type: "fail", message, resumable: opts?.resumable } : { type: "clear-error" }),
    []
  );
  const awaitApproval = useCallback(() => dispatch({ type: "await-approval" }), []);
  const approvalResolved = useCallback(() => dispatch({ type: "approval-resolved" }), []);
  return {
    phase: state.phase,
    sending: isBusyPhase(state.phase),
    error: state.error,
    resumable: state.resumable,
    setSending,
    setError,
    awaitApproval,
    approvalResolved,
  };
}
