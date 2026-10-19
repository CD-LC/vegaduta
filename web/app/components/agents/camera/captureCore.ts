// Pure capture core for CameraPanel: constants, frame capture and the pixel-diff gate.

// Capture core - JPEG, <=1024px long edge, quality 0.8 (~50-150KB per frame,
// comfortably inside the 10MB inline-attachment budget even as a 3-frame set).
export const CAPTURE_LONG_EDGE = 1024;
export const JPEG_QUALITY = 0.8;
// Snapshot staging cap - matches the backend's inline-attachment max (3).
export const MAX_STAGED_FRAMES = 3;
// Pixel-diff gate: 64x36 grayscale thumbnails, mean abs diff normalized to
// 0..1; below this the scene is considered unchanged and nothing is sent.
export const DIFF_W = 64;
export const DIFF_H = 36;
export const DIFF_THRESHOLD = 0.1;
// Hard floor between sent frames regardless of how noisy the diff is.
export const MIN_SEND_GAP_MS = 3000;
export const INTERVAL_OPTIONS = [2, 5, 15];
// "Speak alerts" only reads out replies that plausibly say something - a
// terse "no change" style acknowledgement shorter than this stays silent.
export const SPEAK_MIN_CHARS = 12;
/** One captured frame - the shape AgentPlayground stages and sends. */
export type StagedFrame = {
  /** Client-side key for React lists + removal - never sent to the server. */
  id: string;
  fileName: string;
  mimeType: "image/jpeg";
  /** Raw base64 (no data: prefix) - what the chat POST carries. */
  dataBase64: string;
  /** Full data: URL - thumbnails + the on-device analysis input. */
  dataUrl: string;
  source: "camera" | "screen";
};

export function captureFrame(
  video: HTMLVideoElement,
  source: "camera" | "screen",
  seqRef: { current: number }
): StagedFrame | null {
  const vw = video.videoWidth;
  const vh = video.videoHeight;
  if (!vw || !vh) return null;
  const scale = Math.min(1, CAPTURE_LONG_EDGE / Math.max(vw, vh));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(vw * scale));
  canvas.height = Math.max(1, Math.round(vh * scale));
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  const dataUrl = canvas.toDataURL("image/jpeg", JPEG_QUALITY);
  const comma = dataUrl.indexOf(",");
  if (comma < 0) return null;
  const n = ++seqRef.current;
  return {
    id: `frame-${Date.now()}-${n}`,
    fileName: `frame-${n}.jpg`,
    mimeType: "image/jpeg",
    dataBase64: dataUrl.slice(comma + 1),
    dataUrl,
    source,
  };
}

/**
 * `captureFrame` returns null on a mistimed call - videoWidth/Height can
 * transiently read 0 right after capture starts or right after the tab
 * regains visibility, before the video element has actually painted a frame.
 * One short retry covers that window; a still-null result after it is a real
 * "not ready" state, not a transient blip, and callers surface that instead
 * of failing silently.
 */
export async function captureFrameWithRetry(
  video: HTMLVideoElement,
  source: "camera" | "screen",
  seqRef: { current: number }
): Promise<StagedFrame | null> {
  const first = captureFrame(video, source, seqRef);
  if (first) return first;
  await new Promise((resolve) => setTimeout(resolve, 250));
  return captureFrame(video, source, seqRef);
}

/** 64x36 grayscale sample of the current video frame, for the diff gate. */
export function sampleGray(video: HTMLVideoElement, canvas: HTMLCanvasElement): Uint8ClampedArray | null {
  const ctx = canvas.getContext("2d", { willReadFrequently: true }) as CanvasRenderingContext2D | null;
  if (!ctx || !video.videoWidth || !video.videoHeight) return null;
  ctx.drawImage(video, 0, 0, DIFF_W, DIFF_H);
  const { data } = ctx.getImageData(0, 0, DIFF_W, DIFF_H);
  const gray = new Uint8ClampedArray(DIFF_W * DIFF_H);
  for (let i = 0; i < gray.length; i += 1) {
    const o = i * 4;
    gray[i] = data[o] * 0.299 + data[o + 1] * 0.587 + data[o + 2] * 0.114;
  }
  return gray;
}

export function meanAbsDiff(a: Uint8ClampedArray, b: Uint8ClampedArray): number {
  let sum = 0;
  for (let i = 0; i < a.length; i += 1) sum += Math.abs(a[i] - b[i]);
  return sum / a.length / 255;
}
