/**
 * Frame-sequence encoding for Clip Studio "Motion+" (tier V1).
 *
 * WebCodecs decision (assessed again for V1, same conclusion as assemble.ts's
 * header): `VideoEncoder` emits raw encoded chunks with NO container — turning
 * them into a playable .webm/.mp4 requires a muxer (webm-muxer / mp4-muxer or
 * a hand-rolled EBML/ISOBMFF writer), and this wave is dependency-free by
 * rule. A raw-chunk file is useless to users, and hand-rolling a container is
 * exactly the kind of subtle-corruption surface this codebase avoids. So V1
 * assembly REUSES the proven V0 pipeline: the interpolated frames are played
 * back onto a canvas at the target fps in real time and recorded via
 * assemble.ts's `recordClip` (canvas.captureStream + MediaRecorder, VP9→VP8→
 * WebM→MP4 preference). Consequences carried over unchanged: encoding takes
 * the clip's real duration, and the tab must stay visible (browsers pause
 * canvas capture in hidden tabs) — the UI already warns about both.
 * Revisit with a muxer dependency when one is approved; this module is the
 * seam where a `VideoEncoder` fast-path would slot in without touching
 * callers.
 */

import { recordClip, type ClipResult } from "./assemble";

/**
 * Draw `source` onto the context cover-fit (fill the whole output, cropping
 * the overflowing axis, centered). Motion+ uses cover rather than V0's
 * letterbox because interpolated frames all share one aspect ratio — bars
 * would just shrink the clip. Shared by rife.ts (tensor plumbing) and the
 * playback path below so preview, inference and encode all crop identically.
 */
export function drawCover(
  ctx: CanvasRenderingContext2D,
  source: ImageBitmap | HTMLCanvasElement | HTMLImageElement,
  width: number,
  height: number
): void {
  const anySrc = source as any;
  const srcWidth: number = anySrc.naturalWidth || anySrc.width || 0;
  const srcHeight: number = anySrc.naturalHeight || anySrc.height || 0;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, width, height);
  if (!srcWidth || !srcHeight) return;
  const scale = Math.max(width / srcWidth, height / srcHeight);
  const drawWidth = srcWidth * scale;
  const drawHeight = srcHeight * scale;
  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(source, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight);
}

export interface EncodeFrameSequenceOptions {
  /** Ordered frames, all pre-rendered at (or near) the output size. */
  frames: ImageBitmap[];
  fps: number;
  width: number;
  height: number;
  /** Canvas the recorder captures from — must be in the DOM (see ClipStudio's
   * off-screen-but-painted canvas note). Resized here to width×height. */
  canvas: HTMLCanvasElement;
  /** 0..100, wall-clock based (real-time recording). */
  onProgress?: (percent: number) => void;
  signal?: AbortSignal;
}

/**
 * Encode an interpolated frame sequence into a video Blob. Real-time playback
 * + MediaRecorder per the header note; resolves with the same ClipResult
 * shape V0 produces so the download/preview UI is shared.
 */
export function encodeFrameSequence(options: EncodeFrameSequenceOptions): Promise<ClipResult> {
  const { frames, fps, width, height, canvas, onProgress, signal } = options;
  if (frames.length < 2) {
    return Promise.reject(new Error("Not enough frames to encode a clip."));
  }
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext("2d");
  if (!ctx) {
    return Promise.reject(new Error("Couldn't get a drawing context for the encoding canvas."));
  }
  const lastIndex = frames.length - 1;
  return recordClip({
    canvas,
    drawFrame: (progress: number) => {
      const index = Math.min(lastIndex, Math.max(0, Math.round(progress * lastIndex)));
      drawCover(ctx, frames[index], width, height);
    },
    durationMs: (frames.length / fps) * 1000,
    fps,
    onProgress,
    signal,
  });
}
