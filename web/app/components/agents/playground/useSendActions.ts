"use client";

import type React from "react";
import { retryableUserText } from "../../../lib/failedTurn";
import type { StagedFrame } from "../CameraPanel";
import { isVisionModelCached } from "../../../lib/edge/vision";
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { framesForHistory } from "./format";
import type { PlaygroundContext } from "./sendContext";
import type { useServerTurn } from "./useServerTurn";
import type { useEdgeTurns } from "./useEdgeTurns";

/** The composer's send handler and the other entry points into a turn: retrying a failed reply, and the camera panel's watch-loop callbacks. */
export function useSendActions(ctx: PlaygroundContext, deps: { sendServerTurn: ReturnType<typeof useServerTurn>["sendServerTurn"]; sendViaEdge: ReturnType<typeof useEdgeTurns>["sendViaEdge"]; sendFramesLocally: ReturnType<typeof useEdgeTurns>["sendFramesLocally"] }) {
  const { agent, t, stickToBottomRef, turns, setTurns, sending, setError, input, setInput, inputRef, attachment, setAttachment, attaching, stagedFrames, setStagedFrames, visionOnDevice, setVisionFallback, setFaceIntroNotice, edgeOptIn, tryEnrollFaceFromNameIntroduction } = ctx;
  const { sendServerTurn, sendViaEdge, sendFramesLocally } = deps;
  // "Try again" on a failed reply: drop the failed exchange and send the same user message through the server
  // path again (the failure was a hosted one; a plain text turn is the only kind this is offered for).
  const retryUserText = (index: number) => retryableUserText(turns, index);
  function retryFailedTurn(index: number) {
    const text = retryableUserText(turns, index);
    if (text == null || !agent || sending) return;
    setError(null);
    setTurns((t) => t.slice(0, index - 1));
    void sendServerTurn(text, null, []);
  }

  // React.SyntheticEvent (not FormEvent alone) because the composer calls
  // this from two places: the <form> onSubmit and the textarea's
  // Enter-to-send keydown - preventDefault is the only member used.
  async function handleSend(e: React.SyntheticEvent) {
    e.preventDefault();
    // Stage 4: a turn can be attachment-only (e.g. "here's a photo", no
    // typed text) - only block send when there's truly nothing to send.
    // Staged camera/screen frames count as content the same way.
    if ((!input.trim() && !attachment && stagedFrames.length === 0) || !agent || sending || attaching) return;
    // ChatRequest.message is @NotBlank server-side - an attachment-only send
    // (no typed text) still needs a non-empty message, so fall back to a
    // generic prompt naming the attached category rather than sending "".
    const userMessage =
      input.trim() ||
      (stagedFrames.length > 0
        ? t("stagedFrameFallbackMessage")
        : t("attachmentOnlyMessage", { category: attachment?.category?.toLowerCase() || t("fileFallback") }));
    const sentAttachment = attachment;
    const sentFrames = stagedFrames;
    setInput("");
    // The height above is set imperatively via inline style (onChange), not
    // React state, so clearing `input` alone leaves the textarea visually
    // tall after sending a multi-line message - reset it explicitly.
    if (inputRef.current) inputRef.current.style.height = "auto";
    setAttachment(null);
    setStagedFrames([]);
    setVisionFallback(null);
    setFaceIntroNotice(null);
    setError(null);

    // Flag-gated on-device fast path. Only plain text turns
    // qualify (attachments and staged frames always take their own paths;
    // tool-needing turns fail locally and fall through). A true return means
    // the turn was fully answered on-device; anything else continues into
    // the unchanged server path below. A3: edgeOptIn here is the EFFECTIVE
    // opt-in (stored user choice, else the edge-first default when a local
    // text model is ready) — see the state block above.
    if (edgeEnabled() && edgeOptIn && !sentAttachment && sentFrames.length === 0) {
      const edgeResult = await sendViaEdge(userMessage);
      // `=== true` (not truthiness): with this repo's strict:false tsconfig,
      // only the literal comparison narrows EdgeSendResult's discriminated
      // union so the tail below can read edgeResult.reason - same gotcha
      // sendViaEdge/sendFramesLocally's own comments document.
      if (edgeResult.handled === true) return;
      // A person hitting Cancel means "stop" - silently re-sending the exact
      // same message to a different (hosted) provider right afterward would
      // be the opposite of what they asked for. sendViaEdge's own tail
      // already withdrew the optimistic turns before returning this reason.
      // Fix #6 (P1): "fallback-refused" means the per-browser fallback
      // policy declined to fall through (never/ask-cancelled) - already
      // surfaced via setError in sendViaEdge's own tail, so this must not
      // silently re-send the same message hosted anyway.
      if (edgeResult.reason === "aborted" || edgeResult.reason === "fallback-refused") return;
      // Edge Wave 2 (EDGE-A): prefer the router's PII-scrubbed copy for
      // guardrail-enabled agents over re-sending the raw text hosted.
      await sendServerTurn(edgeResult.hostedMessage ?? userMessage, sentAttachment, sentFrames, {
        edgeFallbackReason: edgeResult.reason,
      });
      return;
    }

    // Edge rule: staged camera/screen frames with "Analyze on this
    // device" active NEVER silently reach the server - the local path either
    // answers, or surfaces its reason and offers the server as an explicit
    // choice (the visionFallback notice above the composer).
    // Face recognition (2026-08-07) - fire-and-forget, runs alongside
    // whichever send path below actually handles the turn; never blocks or
    // delays it. See tryEnrollFaceFromNameIntroduction's own doc comment
    // for exact scope (Show mode only, v1).
    if (sentFrames.length > 0) {
      void tryEnrollFaceFromNameIntroduction(userMessage, sentFrames);
    }

    if (sentFrames.length > 0 && visionOnDevice) {
      await sendFramesLocally(userMessage, sentFrames);
      return;
    }

    // Server-fail -> edge rescue for a frames-carrying turn (mirror of
    // CameraPanel's watch-loop rescue; same S-edge doc rule - rescuing
    // TOWARD the device needs no consent gate, only the reverse does).
    // Engages only when nothing streamed (err.streamedChars, set by
    // sendServerTurn's rethrow path) and the local VLM is actually
    // installed - never on user abort, and deliberately NOT inside
    // sendServerTurn itself, so visionFallback's explicit "send to the
    // server instead" button can never bounce back into the edge path
    // that already failed.
    if (sentFrames.length > 0) {
      try {
        await sendServerTurn(userMessage, sentAttachment, sentFrames, { rethrow: true });
      } catch (err: any) {
        const rescuable =
          err?.name !== "AbortError" && (err?.streamedChars || 0) === 0 && edgeEnabled() && isVisionModelCached();
        if (rescuable) {
          setError(null);
          await sendFramesLocally(userMessage, sentFrames, { rescueFrom: err });
        }
        // Not rescuable: sendServerTurn's own catch already surfaced the
        // error and cleaned up the optimistic turns - nothing more to do.
      }
      return;
    }

    await sendServerTurn(userMessage, sentAttachment, sentFrames);
  }

  // CameraPanel's on-device WATCH path lands its already-completed exchange
  // here - both turns arrive final (the local model doesn't stream), badged
  // watch + on-device.
  function appendLocalVisionTurn({ message, frames, text }: { message: string; frames: StagedFrame[]; text: string }) {
    stickToBottomRef.current = true;
    setTurns((t) => [
      ...t,
      { role: "user", content: message, frames: framesForHistory(frames), watch: true },
      { role: "assistant", content: text, trace: [], pending: false, watch: true, onDevice: true },
    ]);
  }

  // CameraPanel's server WATCH path - one accepted frame becomes one normal
  // transcript turn carrying the standing instruction, the frame, and the
  // session id. Returns the reply text (for the panel's "speak alerts");
  // rethrows so the panel can read err.status.
  function runWatchTurn({
    message,
    frames,
    visionSessionId,
  }: {
    message: string;
    frames?: StagedFrame[] | null;
    visionSessionId?: string | null;
  }) {
    return sendServerTurn(message, null, frames || [], { visionSessionId, watch: true, rethrow: true });
  }

  return { retryUserText, retryFailedTurn, handleSend, appendLocalVisionTurn, runWatchTurn };
}
