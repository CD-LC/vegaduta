import { useEffect, useRef, useState } from "react";
import type { StagedFrame } from "./captureCore";

/**
 * Real-time status parity with watch mode's "watching" pulse + counter,
 * extended to the single-shot ("Show" -> send) path, which has no
 * in-flight indicator of its own otherwise - the normal chat send state
 * lives in AgentPlayground, not CameraPanel, so this infers it from the
 * stagedFrames prop being cleared while a turn is in flight.
 *
 * Staged frames live in `stagedFrames` (owned by AgentPlayground) until the
 * person hits send, at which point the parent clears the list and -
 * separately, after an internal await - flips `busy` to true. Those two prop
 * changes are not guaranteed to land in the same render (sendServerTurn
 * awaits a listToolCalls call before setSending(true)), so this can't just
 * diff `stagedFrames` length: a plain "was non-zero, now zero, while busy"
 * check would also fire for a later plain-text send if it merely raced a
 * stale non-reset flag. The pending flag is true only between a successful
 * stage (`markStaged`) and either the frame(s) being sent or explicitly
 * removed down to zero (`clearPending`), and survives across that gap.
 */
export function useSingleShotAnalyzing(stagedFrames: StagedFrame[], busy: boolean, agentId: unknown) {
  const [singleShotAnalyzing, setSingleShotAnalyzing] = useState(false);
  const hasPendingStagedSendRef = useRef(false);

  // Agent switch drops the indicator and any pending-send flag.
  useEffect(() => {
    setSingleShotAnalyzing(false);
    hasPendingStagedSendRef.current = false;
  }, [agentId]);
  useEffect(() => {
    if (stagedFrames.length === 0 && hasPendingStagedSendRef.current && busy) {
      setSingleShotAnalyzing(true);
      hasPendingStagedSendRef.current = false;
    }
  }, [stagedFrames, busy]);
  useEffect(() => {
    if (!busy) setSingleShotAnalyzing(false);
  }, [busy]);

  return {
    singleShotAnalyzing,
    markStaged: () => {
      hasPendingStagedSendRef.current = true;
    },
    clearPending: () => {
      hasPendingStagedSendRef.current = false;
    },
  };
}
