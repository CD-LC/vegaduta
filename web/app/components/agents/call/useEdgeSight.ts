"use client";

// S-edge live sight (client half of voice-agent/app/edge_sight.py), lifted out
// of CallPanel.tsx unchanged. The camera is captured and analysed LOCALLY
// (never published to the room); only text reports leave the device over the
// "edge-sight" data topic. All per-frame state lives in refs, so nothing here
// re-renders per frame.

import { useRef, useState, type MutableRefObject, type RefObject } from "react";
import type { Room } from "livekit-client";
import { edgeFallbackLabel } from "../../../lib/edge/capabilities";
import { analyzeFramesLocally } from "../../../lib/edge/vision";
import { errorMessage } from "../../../lib/errors";

// Same gate constants as CameraPanel's watch loop (the shared-contract
// pixel-diff gate): tiny grayscale thumbnail diffed against the last frame
// actually ANALYZED, plus a hard floor between analyses. The local VLM
// downscales to 512px internally, so capture at that size directly.
export const EDGE_SIGHT_TICK_MS = 2000;
export const EDGE_SIGHT_MIN_GAP_MS = 3000;
export const EDGE_SIGHT_DIFF_THRESHOLD = 0.1;
export const EDGE_SIGHT_DIFF_W = 64;
export const EDGE_SIGHT_DIFF_H = 36;
export const EDGE_SIGHT_CAPTURE_EDGE = 512;
export const EDGE_SIGHT_PROMPT =
  "Describe concisely what the camera currently shows, focusing on anything new or notable.";
export const EDGE_SIGHT_SCREEN_PROMPT =
  "Describe concisely what the shared screen currently shows, focusing on anything new or notable.";

/** Mean absolute grayscale difference between two equally sized frames, 0..1. */
export function grayFrameDiff(current: Uint8ClampedArray, last: Uint8ClampedArray): number {
  if (current.length === 0) return 0;
  let total = 0;
  for (let i = 0; i < current.length; i++) total += Math.abs(current[i] - last[i]);
  return total / current.length / 255;
}

/** RGBA pixels to the luma plane the diff gate compares. */
export function rgbaToGray(rgba: ArrayLike<number>, pixels: number): Uint8ClampedArray {
  const gray = new Uint8ClampedArray(pixels);
  for (let i = 0; i < pixels; i++) {
    const o = i * 4;
    gray[i] = (rgba[o] * 299 + rgba[o + 1] * 587 + rgba[o + 2] * 114) / 1000;
  }
  return gray;
}

interface UseEdgeSightArgs {
  t: (key: string, values?: Record<string, string | number>) => string;
  roomRef: MutableRefObject<Room | null>;
  localVideoRef: RefObject<HTMLVideoElement | null>;
  setCameraOn: (on: boolean) => void;
  setCallNotice: (notice: string | null) => void;
}

export function useEdgeSight({ t, roomRef, localVideoRef, setCameraOn, setCallNotice }: UseEdgeSightArgs) {
  // Whether this call is publishing/analysing sight from the local device;
  // drives the shared self-view label in CallStatusNotices.
  const [edgeSightActive, setEdgeSightActive] = useState(false);
  // Whether the analysed stream is the camera or a shared screen - the
  // CallControlsBar shows a monitor icon while the screen is up.
  const [edgeSightSource, setEdgeSightSource] = useState<"camera" | "screen" | null>(null);
  // The interval tick reads the source through a ref - setInterval closures
  // capture state once, and a camera→screen switch mid-call must not be stale.
  const edgeSourceRef = useRef<"camera" | "screen">("camera");
  // edgeSightModeRef: eligibility computed once per call at startCall time
  // (edge flag on + local VLM already installed - a call button must never
  // trigger a ~500MB model download). sightRoomRef: this call runs in a
  // server-sight room, so the edge path must stay out of toggleCamera.
  const edgeSightModeRef = useRef(false);
  const sightRoomRef = useRef(false);
  const edgeStreamRef = useRef<MediaStream | null>(null);
  const edgeLoopRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const edgeGrayRef = useRef<Uint8ClampedArray | null>(null);
  const edgeLastSentRef = useRef(0);
  const edgeInFlightRef = useRef(false);
  const edgeCanvasRef = useRef<HTMLCanvasElement | null>(null);

  async function startEdgeSight(room: Room, source: "camera" | "screen" = "camera") {
    // getUserMedia / getDisplayMedia directly - deliberately NOT
    // room.setCameraEnabled / setScreenShareEnabled, which would publish the
    // track. The stream exists only inside this tab; the screen picker the
    // browser shows is itself the consent step (a user gesture is required).
    const stream =
      source === "screen"
        ? await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
        : await navigator.mediaDevices.getUserMedia({ video: true, audio: false });
    setEdgeSightSource(source);
    edgeSourceRef.current = source;
    edgeStreamRef.current = stream;
    edgeGrayRef.current = null;
    edgeLastSentRef.current = 0;
    edgeInFlightRef.current = false;
    setEdgeSightActive(true);
    setCameraOn(true);
    // The browser's own "camera in use" teardown (OS-level revoke, another
    // app grabbing the device) ends edge sight cleanly, same as CameraPanel.
    stream.getVideoTracks().forEach((track) => {
      track.onended = () => stopEdgeSight();
    });
    edgeLoopRef.current = setInterval(() => {
      void edgeSightTick(room);
    }, EDGE_SIGHT_TICK_MS);
  }

  function stopEdgeSight(notice: string | null = null) {
    if (edgeLoopRef.current) {
      clearInterval(edgeLoopRef.current);
      edgeLoopRef.current = null;
    }
    edgeStreamRef.current?.getTracks().forEach((track) => track.stop());
    edgeStreamRef.current = null;
    edgeGrayRef.current = null;
    edgeInFlightRef.current = false;
    setEdgeSightActive(false);
    setEdgeSightSource(null);
    setCameraOn(false);
    if (notice) setCallNotice(notice);
  }

  async function edgeSightTick(room: Room) {
    if (edgeInFlightRef.current || !edgeStreamRef.current || roomRef.current !== room) return;
    // Trust rule (shared with CameraPanel's watch loop): nothing is sampled
    // while the tab is hidden.
    if (typeof document !== "undefined" && document.visibilityState === "hidden") return;
    const el = localVideoRef.current;
    if (!el || el.readyState < 2 || !el.videoWidth) return;
    let canvas = edgeCanvasRef.current;
    if (!canvas) {
      canvas = document.createElement("canvas");
      edgeCanvasRef.current = canvas;
    }
    // Gate pass: tiny grayscale diff vs the last frame actually analyzed.
    canvas.width = EDGE_SIGHT_DIFF_W;
    canvas.height = EDGE_SIGHT_DIFF_H;
    const gateCtx = canvas.getContext("2d", { willReadFrequently: true });
    if (!gateCtx) return;
    gateCtx.drawImage(el, 0, 0, EDGE_SIGHT_DIFF_W, EDGE_SIGHT_DIFF_H);
    const rgba = gateCtx.getImageData(0, 0, EDGE_SIGHT_DIFF_W, EDGE_SIGHT_DIFF_H).data;
    const gray = rgbaToGray(rgba, EDGE_SIGHT_DIFF_W * EDGE_SIGHT_DIFF_H);
    const last = edgeGrayRef.current;
    if (last && grayFrameDiff(gray, last) < EDGE_SIGHT_DIFF_THRESHOLD) return;
    if (Date.now() - edgeLastSentRef.current < EDGE_SIGHT_MIN_GAP_MS) return;
    // Full capture at the VLM's own working size.
    const scale = EDGE_SIGHT_CAPTURE_EDGE / Math.max(el.videoWidth, el.videoHeight);
    canvas.width = Math.max(1, Math.round(el.videoWidth * Math.min(1, scale)));
    canvas.height = Math.max(1, Math.round(el.videoHeight * Math.min(1, scale)));
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.drawImage(el, 0, 0, canvas.width, canvas.height);
    const dataUrl = canvas.toDataURL("image/jpeg", 0.8);
    edgeGrayRef.current = gray;
    edgeLastSentRef.current = Date.now();
    edgeInFlightRef.current = true;
    try {
      const result = await analyzeFramesLocally(
        [{ dataUrl }],
        edgeSourceRef.current === "screen" ? EDGE_SIGHT_SCREEN_PROMPT : EDGE_SIGHT_PROMPT
      );
      if (roomRef.current !== room || !edgeStreamRef.current) return;
      if (result.handled === false) {
        // The in-call rule is absolute: raw frames NEVER go to the server
        // from edge mode - a local failure stops sight (voice continues)
        // rather than silently escalating. Hanging up and starting an
        // explicit server-sight call is the person's consented alternative.
        stopEdgeSight(t("edgeSightStopped", { reason: edgeFallbackLabel(result.reason) }));
        return;
      }
      const text = (result.text || "").trim();
      if (!text) return;
      await room.localParticipant.publishData(new TextEncoder().encode(JSON.stringify({ text })), {
        reliable: true,
        topic: "edge-sight",
      });
    } catch (err) {
      console.warn(`[Voice] edge-sight tick failed (sight stops, call continues):`, err);
      stopEdgeSight(t("edgeSightStopped", { reason: errorMessage(err, "analysis failed") }));
    } finally {
      edgeInFlightRef.current = false;
    }
  }

  return {
    edgeSightActive,
    edgeSightSource,
    edgeSightModeRef,
    sightRoomRef,
    edgeStreamRef,
    startEdgeSight,
    stopEdgeSight,
  };
}
