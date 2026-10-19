/**
 * Ken Burns rendering engine (tier V0).
 *
 * Pure, framework-free canvas maths: given a still image and a motion spec,
 * this module knows how to draw any single frame of an eased pan/zoom
 * animation onto a 2D canvas context. It owns NO timing, NO recording and NO
 * React — the preview loop and the MediaRecorder pipeline (assemble.ts) both
 * drive the same `drawFrame(ctx, progress)` so what you preview is exactly
 * what gets encoded.
 *
 * Coordinate model: a "camera rect" is a crop of the source image, expressed
 * in normalized image coordinates (0..1 fractions of the image's width and
 * height). Presets are described as center+scale pairs and resolved into
 * start/end rects that always keep the image's own aspect ratio, so the
 * animation never distorts; the crop is then letterboxed into the output
 * canvas (black bars when the image aspect differs from the output aspect).
 */

export type MotionPreset =
  | "zoom-in"
  | "zoom-out"
  | "pan-left"
  | "pan-right"
  | "diagonal"
  | "slow-drift";

/** Fractions of the source image (0..1), NOT pixels. */
export interface NormalizedRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface MotionSpec {
  preset: MotionPreset;
  /**
   * Optional explicit camera path, overriding the preset's built-in rects.
   * Both must be provided together; values are clamped into the image.
   */
  startRect?: NormalizedRect;
  endRect?: NormalizedRect;
}

export interface OutputSize {
  width: number;
  height: number;
}

/** Anything drawImage accepts that also exposes intrinsic dimensions. */
export type KenBurnsSource = ImageBitmap | HTMLImageElement | HTMLCanvasElement;

export const MIN_DURATION_MS = 3000;
export const MAX_DURATION_MS = 10000;
export const SUPPORTED_FPS = [24, 30] as const;

export const MOTION_PRESETS: Array<{ id: MotionPreset; label: string; description: string }> = [
  { id: "zoom-in", label: "Zoom in", description: "Slow push toward the center" },
  { id: "zoom-out", label: "Zoom out", description: "Start close, pull back to reveal" },
  { id: "pan-left", label: "Pan left", description: "Glide across from right to left" },
  { id: "pan-right", label: "Pan right", description: "Glide across from left to right" },
  { id: "diagonal", label: "Diagonal", description: "Drift corner to corner while zooming" },
  { id: "slow-drift", label: "Slow drift", description: "Barely-there cinematic movement" },
];

export function clampDuration(ms: number): number {
  if (!Number.isFinite(ms)) return MIN_DURATION_MS;
  return Math.min(MAX_DURATION_MS, Math.max(MIN_DURATION_MS, ms));
}

export function easeInOutCubic(t: number): number {
  const x = Math.min(1, Math.max(0, t));
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
}

export function sourceSize(src: KenBurnsSource): { width: number; height: number } {
  // HTMLImageElement's `width`/`height` reflect layout, not pixels — prefer
  // the natural dimensions when present.
  const anySrc = src as any;
  const width = anySrc.naturalWidth || anySrc.width || 0;
  const height = anySrc.naturalHeight || anySrc.height || 0;
  return { width, height };
}

// Presets as camera center + zoom scale (scale 1 = the full image visible).
// Kept modest on purpose: subtle movement reads as intentional; big swings
// read as a broken screensaver.
const PRESET_CAMERAS: Record<
  MotionPreset,
  { start: { cx: number; cy: number; scale: number }; end: { cx: number; cy: number; scale: number } }
> = {
  "zoom-in": { start: { cx: 0.5, cy: 0.5, scale: 1 }, end: { cx: 0.5, cy: 0.5, scale: 0.68 } },
  "zoom-out": { start: { cx: 0.5, cy: 0.5, scale: 0.68 }, end: { cx: 0.5, cy: 0.5, scale: 1 } },
  "pan-left": { start: { cx: 0.62, cy: 0.5, scale: 0.76 }, end: { cx: 0.38, cy: 0.5, scale: 0.76 } },
  "pan-right": { start: { cx: 0.38, cy: 0.5, scale: 0.76 }, end: { cx: 0.62, cy: 0.5, scale: 0.76 } },
  diagonal: { start: { cx: 0.4, cy: 0.4, scale: 0.74 }, end: { cx: 0.62, cy: 0.62, scale: 0.62 } },
  "slow-drift": { start: { cx: 0.49, cy: 0.48, scale: 1 }, end: { cx: 0.53, cy: 0.53, scale: 0.9 } },
};

interface PixelRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

function cameraToPixelRect(
  camera: { cx: number; cy: number; scale: number },
  imageWidth: number,
  imageHeight: number
): PixelRect {
  const width = imageWidth * camera.scale;
  const height = imageHeight * camera.scale;
  return clampRect(
    { x: camera.cx * imageWidth - width / 2, y: camera.cy * imageHeight - height / 2, width, height },
    imageWidth,
    imageHeight
  );
}

function normalizedToPixelRect(rect: NormalizedRect, imageWidth: number, imageHeight: number): PixelRect {
  return clampRect(
    {
      x: rect.x * imageWidth,
      y: rect.y * imageHeight,
      width: rect.width * imageWidth,
      height: rect.height * imageHeight,
    },
    imageWidth,
    imageHeight
  );
}

/** Keep the crop fully inside the image (sliding it back in bounds, then shrinking if it's simply too big). */
function clampRect(rect: PixelRect, imageWidth: number, imageHeight: number): PixelRect {
  let { x, y, width, height } = rect;
  width = Math.min(Math.max(1, width), imageWidth);
  height = Math.min(Math.max(1, height), imageHeight);
  x = Math.min(Math.max(0, x), imageWidth - width);
  y = Math.min(Math.max(0, y), imageHeight - height);
  return { x, y, width, height };
}

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

function lerpRect(a: PixelRect, b: PixelRect, t: number): PixelRect {
  return {
    x: lerp(a.x, b.x, t),
    y: lerp(a.y, b.y, t),
    width: lerp(a.width, b.width, t),
    height: lerp(a.height, b.height, t),
  };
}

/** Resolve a motion spec against a concrete image into start/end pixel crops. */
export function resolveMotion(
  motion: MotionSpec,
  imageWidth: number,
  imageHeight: number
): { start: PixelRect; end: PixelRect } {
  if (motion.startRect && motion.endRect) {
    return {
      start: normalizedToPixelRect(motion.startRect, imageWidth, imageHeight),
      end: normalizedToPixelRect(motion.endRect, imageWidth, imageHeight),
    };
  }
  const cameras = PRESET_CAMERAS[motion.preset] || PRESET_CAMERAS["zoom-in"];
  return {
    start: cameraToPixelRect(cameras.start, imageWidth, imageHeight),
    end: cameraToPixelRect(cameras.end, imageWidth, imageHeight),
  };
}

export interface KenBurnsRenderer {
  /** Draw the frame at `progress` (0..1, un-eased — easing happens inside). */
  drawFrame(ctx: CanvasRenderingContext2D, progress: number): void;
  output: OutputSize;
}

/**
 * Build a renderer bound to one image + motion + output size. The caller owns
 * the canvas/context; drawFrame is deterministic and idempotent, so both a
 * looping preview and a strict frame-by-frame recording can share it.
 */
export function createKenBurnsRenderer(
  image: KenBurnsSource,
  motion: MotionSpec,
  output: OutputSize
): KenBurnsRenderer {
  const { width: imageWidth, height: imageHeight } = sourceSize(image);
  if (!imageWidth || !imageHeight) {
    throw new Error("The selected image has no dimensions — it may not have finished loading.");
  }
  const { start, end } = resolveMotion(motion, imageWidth, imageHeight);

  return {
    output,
    drawFrame(ctx: CanvasRenderingContext2D, progress: number) {
      const t = easeInOutCubic(progress);
      const crop = lerpRect(start, end, t);

      // Letterbox: scale the crop to fit inside the output while keeping the
      // crop's aspect ratio, centered on a black background.
      const scale = Math.min(output.width / crop.width, output.height / crop.height);
      const destWidth = crop.width * scale;
      const destHeight = crop.height * scale;
      const destX = (output.width - destWidth) / 2;
      const destY = (output.height - destHeight) / 2;

      ctx.fillStyle = "#000";
      ctx.fillRect(0, 0, output.width, output.height);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = "high";
      ctx.drawImage(image, crop.x, crop.y, crop.width, crop.height, destX, destY, destWidth, destHeight);
    },
  };
}

/**
 * Decode a Blob (downloaded generated image or a local file) into a drawable
 * source. Prefers ImageBitmap (decodes off the main thread in Chromium);
 * falls back to an HTMLImageElement for older Safari.
 */
export async function loadImageSource(blob: Blob): Promise<KenBurnsSource> {
  if (typeof createImageBitmap === "function") {
    try {
      return await createImageBitmap(blob);
    } catch {
      // Fall through to the <img> decode path (e.g. unsupported bitmap type).
    }
  }
  return await new Promise<HTMLImageElement>((resolve, reject) => {
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      resolve(img);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("Couldn't decode that image."));
    };
    img.src = url;
  });
}

/** Release a source obtained from loadImageSource. */
export function releaseImageSource(src: KenBurnsSource | null): void {
  if (src && typeof (src as ImageBitmap).close === "function") {
    try {
      (src as ImageBitmap).close();
    } catch {
      // Already closed — nothing to do.
    }
  }
}
