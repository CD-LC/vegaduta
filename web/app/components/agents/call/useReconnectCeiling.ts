"use client";

// Part 5(c) fix #3: arms a client-side ceiling on how long "reconnecting..."
// is allowed to sit on screen, reusing requestLifecycle.ts's controller/timer
// machinery (rather than a hand-rolled setTimeout) so a stuck reconnect gets
// the same TIMED_OUT classification every other long-running call in this app
// gets. The operation never resolves on its own - it only settles via the
// controller's connect-timeout timer firing, or via disarm() calling cancel()
// once the room actually recovers (or the call ends).

import { useRef, type MutableRefObject } from "react";
import type { Room } from "livekit-client";
import { runRequestLifecycle, type RequestLifecycleController } from "../../../lib/requestLifecycle";
import { RECONNECT_CEILING_MS } from "../CallPanel/constants";

interface UseReconnectCeilingArgs {
  agentId: string | undefined;
  roomRef: MutableRefObject<Room | null>;
  /** Hangs up; the returned promise is awaited before the error is shown. */
  endCall: () => Promise<void> | undefined;
  setCallError: (message: string) => void;
  setStatus: (status: string) => void;
}

export function useReconnectCeiling({ agentId, roomRef, endCall, setCallError, setStatus }: UseReconnectCeilingArgs) {
  // The active reconnect-ceiling controller, or null when no reconnect is in flight.
  const reconnectLifecycleRef = useRef<RequestLifecycleController<void> | null>(null);

  function arm(room: Room) {
    if (reconnectLifecycleRef.current) return; // already armed for this drop
    const controller = runRequestLifecycle<void>(
      (ctx) =>
        new Promise<void>((_resolve, reject) => {
          const onAbort = () => reject(new Error("reconnect wait ended"));
          if (ctx.signal.aborted) {
            onAbort();
            return;
          }
          ctx.signal.addEventListener("abort", onAbort, { once: true });
        }),
      {
        target: `voice-call-reconnect:${agentId ?? "unknown"}`,
        surface: "call-reconnect",
        timeoutPolicy: {
          connectTimeoutMs: RECONNECT_CEILING_MS,
          streamIdleTimeoutMs: 0,
          absoluteTimeoutMs: RECONNECT_CEILING_MS,
        },
        retryPolicy: false,
        circuitBreaker: false,
      }
    );
    reconnectLifecycleRef.current = controller;
    void controller.done.then(async (result) => {
      if (reconnectLifecycleRef.current !== controller) return; // superseded/cleared already
      reconnectLifecycleRef.current = null;
      // A clean cancel() (room recovered, or the call ended some other way)
      // reports failureClass "aborted" - only a real ceiling hit (TIMED_OUT,
      // failureClass "connect-timeout") should force the issue.
      if (!result.ok && result.snapshot.failureClass !== "aborted" && roomRef.current === room) {
        console.warn(`[Voice] Reconnect ceiling (${RECONNECT_CEILING_MS}ms) exceeded - forcing hangup`);
        // Fix #3 (P1): AWAIT endCall's disconnect before setting the error,
        // otherwise the room's own Disconnected handler (CLIENT_INITIATED)
        // resets status to idle without ever touching callError, leaving a
        // permanent stuck error box under an "idle" pill.
        await endCall();
        setCallError("Still trying to reconnect — this network may be blocking the call. Hang up and try again.");
        setStatus("error");
      }
    });
  }

  function disarm() {
    reconnectLifecycleRef.current?.cancel();
    reconnectLifecycleRef.current = null;
  }

  return { armReconnectCeiling: arm, disarmReconnectCeiling: disarm };
}
