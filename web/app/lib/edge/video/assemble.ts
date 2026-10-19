/**
 * Clip assembly pipeline (tier V0).
 *
 * Records a Ken Burns animation into a video Blob entirely on-device via
 * `canvas.captureStream(fps)` + `MediaRecorder`. Framework-free; the caller
 * (Clip Studio) supplies a canvas sized to the output and a `drawFrame`
 * closure (from kenburns.ts) and gets back a Blob + mime type.
 *
 * Codec preference: VP9 WebM → VP8 WebM → plain WebM → MP4 (Safari 14.1+
 * records H.264 MP4 and does not support WebM) → browser default. The first
 * `MediaRecorder.isTypeSupported` hit wins.
 *
 * WebCodecs note: a `VideoEncoder` fast-path was considered and deliberately
 * NOT implemented. WebCodecs emits raw encoded chunks with no container;
 * producing a playable .webm/.mp4 would require a muxer (webm-muxer /
 * mp4-muxer or hand-rolled EBML), and this wave is dependency-free by rule.
 * Revisit when a muxer dependency is approved.
 *
 * Timing model: captureStream/MediaRecorder record in REAL TIME, so the loop
 * below runs for `durationMs` of wall-clock time, drawing on a fixed
 * timestep (one tick per 1/fps) and clamping progress to the wall clock.
 * Consequence (verified behaviour, not folklore): browsers throttle timers
 * and freeze requestAnimationFrame in hidden tabs, and Chromium pauses
 * canvas capture frame delivery when the tab is not visible — so the tab
 * must stay VISIBLE (not necessarily focused) for the whole recording, or
 * the clip comes out with frozen/missing frames. The UI warns about this.
 */

export interface ClipRecordingSupport {
  supported: boolean;
  /** Chosen container/codec string, "" = browser default. Only when supported. */
  mimeType?: string;
  /** Human-readable explanation when unsupported. */
  reason?: string;
}

export interface RecordClipOptions {
  /** Canvas already sized to the exact output resolution. */
  canvas: HTMLCanvasElement;
  /** Draws the frame for progress 0..1 onto that canvas (kenburns renderer). */
  drawFrame: (progress: number) => void;
  durationMs: number;
  fps: number;
  /** Called with 0..100, derived from wall-clock elapsed / duration. */
  onProgress?: (percent: number) => void;
  /** Abort to cancel; the promise rejects with an Error named "AbortError". */
  signal?: AbortSignal;
}

export interface ClipResult {
  blob: Blob;
  mimeType: string;
  extension: "webm" | "mp4";
}

const MIME_CANDIDATES = [
  "video/webm;codecs=vp9",
  "video/webm;codecs=vp8",
  "video/webm",
  "video/mp4;codecs=avc1.42E01E",
  "video/mp4",
];

/** Roughly YouTube-720p quality; small enough that a 10s clip stays a few MB. */
const VIDEO_BITS_PER_SECOND = 8_000_000;

export function detectClipRecordingSupport(): ClipRecordingSupport {
  if (typeof window === "undefined") {
    return { supported: false, reason: "Recording only works in the browser." };
  }
  if (typeof (HTMLCanvasElement.prototype as any).captureStream !== "function") {
    return {
      supported: false,
      reason: "This browser can't stream a canvas (canvas.captureStream is missing). Try a recent Chrome, Edge, Firefox, or Safari.",
    };
  }
  if (typeof (window as any).MediaRecorder === "undefined") {
    return {
      supported: false,
      reason: "This browser has no MediaRecorder, so it can't encode video locally. Try a recent Chrome, Edge, Firefox, or Safari.",
    };
  }
  for (const candidate of MIME_CANDIDATES) {
    try {
      if (MediaRecorder.isTypeSupported(candidate)) {
        return { supported: true, mimeType: candidate };
      }
    } catch {
      // Some engines throw on unknown strings — keep probing.
    }
  }
  // MediaRecorder exists but reported nothing: let the browser pick its
  // default container rather than refusing outright.
  return { supported: true, mimeType: "" };
}

function extensionFor(mimeType: string): "webm" | "mp4" {
  return /mp4/i.test(mimeType) ? "mp4" : "webm";
}

/**
 * Record `drawFrame` for exactly `durationMs` and resolve with the encoded
 * clip. Non-blocking: everything runs on timers, so the page stays
 * interactive throughout.
 */
export function recordClip(options: RecordClipOptions): Promise<ClipResult> {
  const { canvas, drawFrame, durationMs, fps, onProgress, signal } = options;

  return new Promise<ClipResult>((resolve, reject) => {
    const support = detectClipRecordingSupport();
    if (!support.supported) {
      reject(new Error(support.reason || "Clip recording isn't supported in this browser."));
      return;
    }
    if (signal?.aborted) {
      reject(abortError());
      return;
    }

    const frameIntervalMs = 1000 / fps;
    const totalFrames = Math.max(2, Math.round((durationMs / 1000) * fps));

    // Paint the first frame before capture starts so the recording never
    // opens on a blank/black canvas.
    drawFrame(0);

    let stream: MediaStream;
    try {
      stream = (canvas as any).captureStream(fps);
    } catch (err: any) {
      reject(new Error("Couldn't start canvas capture: " + (err?.message || err)));
      return;
    }

    let recorder: MediaRecorder;
    try {
      recorder = support.mimeType
        ? new MediaRecorder(stream, { mimeType: support.mimeType, videoBitsPerSecond: VIDEO_BITS_PER_SECOND })
        : new MediaRecorder(stream, { videoBitsPerSecond: VIDEO_BITS_PER_SECOND });
    } catch {
      // Bitrate or mime hint rejected — retry with no options at all.
      try {
        recorder = new MediaRecorder(stream);
      } catch (err2: any) {
        stopTracks(stream);
        reject(new Error("Couldn't start the video encoder: " + (err2?.message || err2)));
        return;
      }
    }

    const chunks: BlobPart[] = [];
    let timer: ReturnType<typeof setTimeout> | null = null;
    let settled = false;
    let aborted = false;
    const startedAt = performance.now();

    const cleanup = () => {
      if (timer !== null) clearTimeout(timer);
      timer = null;
      signal?.removeEventListener("abort", onAbort);
      stopTracks(stream);
    };

    const fail = (err: Error) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(err);
    };

    const onAbort = () => {
      aborted = true;
      try {
        if (recorder.state !== "inactive") recorder.stop();
        else fail(abortError());
      } catch {
        fail(abortError());
      }
    };
    signal?.addEventListener("abort", onAbort);

    recorder.ondataavailable = (event: BlobEvent) => {
      if (event.data && event.data.size > 0) chunks.push(event.data);
    };
    recorder.onerror = () => {
      fail(new Error("The video encoder reported an error mid-recording."));
    };
    recorder.onstop = () => {
      if (settled) return;
      settled = true;
      cleanup();
      if (aborted) {
        reject(abortError());
        return;
      }
      const mimeType = recorder.mimeType || support.mimeType || "video/webm";
      const blob = new Blob(chunks, { type: mimeType });
      if (blob.size === 0) {
        reject(
          new Error(
            "Recording finished but produced no data. If this tab was hidden during recording, keep it visible and try again."
          )
        );
        return;
      }
      onProgress?.(100);
      resolve({ blob, mimeType, extension: extensionFor(mimeType) });
    };

    const finish = () => {
      // Hold the last frame briefly so captureStream delivers it before stop.
      drawFrame(1);
      timer = setTimeout(() => {
        try {
          if (recorder.state !== "inactive") recorder.stop();
        } catch {
          fail(new Error("Couldn't finalize the recording."));
        }
      }, Math.max(120, frameIntervalMs * 2));
    };

    // Fixed-timestep frame loop, terminated by the wall clock (MediaRecorder
    // records real time, so wall time — not frame count — defines "done").
    let nextFrameIndex = 1;
    const tick = () => {
      timer = null;
      if (settled || aborted) return;
      const elapsed = performance.now() - startedAt;
      if (elapsed >= durationMs) {
        onProgress?.(100);
        finish();
        return;
      }
      // Catch up to wherever the wall clock says we should be (timers can
      // lag under load); draw at most the frame for "now".
      const dueFrame = Math.min(totalFrames - 1, Math.floor(elapsed / frameIntervalMs));
      if (dueFrame >= nextFrameIndex) {
        drawFrame(dueFrame / (totalFrames - 1));
        nextFrameIndex = dueFrame + 1;
      }
      onProgress?.(Math.min(99, Math.round((elapsed / durationMs) * 100)));
      const nextDue = startedAt + nextFrameIndex * frameIntervalMs;
      timer = setTimeout(tick, Math.max(0, nextDue - performance.now()));
    };

    try {
      // Timeslice keeps memory flat and means an abort still has data so far.
      recorder.start(250);
    } catch (err: any) {
      fail(new Error("Couldn't start recording: " + (err?.message || err)));
      return;
    }
    onProgress?.(0);
    timer = setTimeout(tick, frameIntervalMs);
  });
}

function stopTracks(stream: MediaStream) {
  try {
    stream.getTracks().forEach((track) => track.stop());
  } catch {
    // Track already stopped.
  }
}

function abortError(): Error {
  const err = new Error("Clip recording was cancelled.");
  err.name = "AbortError";
  return err;
}
