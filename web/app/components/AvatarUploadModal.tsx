"use client";

import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { X, Upload, ZoomIn } from "lucide-react";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { Button } from "./ui/button";
import { useDialogFocus } from "../lib/hooks/useDialogFocus";
import { useTranslations } from "../lib/i18n";

// Square crop viewport (CSS px) the person drags/zooms the picture inside -
// matches the circular avatar it will end up rendered as everywhere else in
// the app. Kept modest since this is a small profile picture, not a photo
// editor.
const VIEWPORT_SIZE = 240;
// What actually gets uploaded - "square PNG/JPEG, e.g. 256x256" per spec.
// Comfortably larger than every place this renders (the 32px AppShell
// avatar included) without producing an oversized file.
const OUTPUT_SIZE = 256;
const MIN_ZOOM = 1;
const MAX_ZOOM = 3;

type Offset = { x: number; y: number };

function clampOffset(offset: Offset, scale: number, naturalWidth: number, naturalHeight: number): Offset {
  const drawnWidth = naturalWidth * scale;
  const drawnHeight = naturalHeight * scale;
  // The image always fully covers the viewport (cover-fit + zoom never goes
  // below 1x) - so the valid offset range keeps every edge of the drawn
  // image at or beyond the viewport's own edges, never leaving a gap.
  const minX = Math.min(0, VIEWPORT_SIZE - drawnWidth);
  const minY = Math.min(0, VIEWPORT_SIZE - drawnHeight);
  return {
    x: Math.min(0, Math.max(minX, offset.x)),
    y: Math.min(0, Math.max(minY, offset.y)),
  };
}

/**
 * Profile-picture upload + crop, opened from AppShell's avatar (next to
 * Sign out). Direct feedback's ask was "pick an image, crop it to a square
 * with drag-to-position + zoom, confirm to upload" - no new npm dependency
 * per that same ask, so this is a plain HTML5 <canvas> crop rather than
 * react-easy-crop/react-image-crop (neither is in web/package.json, and
 * pulling one in for a single small modal isn't worth the new dependency):
 *
 *   1. Pick a file -> loaded into an offscreen `Image` (imageRef), not an
 *      <img> in the DOM - only the crop canvas ever paints anything.
 *   2. The crop canvas (VIEWPORT_SIZE x VIEWPORT_SIZE, clipped to a circle
 *      via CSS so it previews exactly like the avatar it becomes) redraws on
 *      every pan/zoom change - `offset` is the drawn image's top-left corner
 *      in viewport coordinates, `zoom` (1-3x) multiplies a cover-fit
 *      `baseScale` so the image always fully fills the viewport, and
 *      clampOffset keeps it that way after every drag or zoom change.
 *   3. Confirm maps that same visible region onto a fresh OUTPUT_SIZE
 *      canvas (source rect = the inverse of the viewport transform) and
 *      exports it via canvas.toBlob - the actual bytes POSTed to
 *      /api/account/avatar (api.uploadAccountAvatar).
 *
 * `onUploaded` receives the refreshed AccountResponse ({avatarUrl now set})
 * so AppShell can update the displayed avatar without a separate refetch.
 */
export default function AvatarUploadModal({
  open,
  onClose,
  onUploaded,
}: {
  open: boolean;
  onClose: () => void;
  onUploaded: (account: any) => void;
}) {
  const [mounted, setMounted] = useState(false);
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [naturalSize, setNaturalSize] = useState<{ w: number; h: number } | null>(null);
  const [zoom, setZoom] = useState(MIN_ZOOM);
  const [offset, setOffset] = useState<Offset>({ x: 0, y: 0 });
  const [uploading, setUploading] = useState(false);

  const imageRef = useRef<HTMLImageElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const dragRef = useRef<{ startX: number; startY: number; startOffset: Offset } | null>(null);

  useEffect(() => setMounted(true), []);

  // Reset to the "pick a file" state every time the modal (re)opens, so a
  // previous session's picked image never lingers into a fresh open.
  useEffect(() => {
    if (!open) return;
    setImageUrl(null);
    setNaturalSize(null);
    setZoom(MIN_ZOOM);
    setOffset({ x: 0, y: 0 });
    setUploading(false);
  }, [open]);

  // Escape-to-close, initial focus, Tab trap and focus restore are handled by
  // useDialogFocus inside AvatarCropDialog below, which mounts fresh each
  // time `open` flips true.

  // Revoke the object URL whenever it's replaced/unmounted - the usual
  // createObjectURL cleanup, otherwise each picked file leaks its blob.
  useEffect(() => {
    return () => {
      if (imageUrl) URL.revokeObjectURL(imageUrl);
    };
  }, [imageUrl]);

  function baseScale(naturalWidth: number, naturalHeight: number): number {
    // Cover-fit: the smaller-relative-to-viewport dimension sets the scale
    // that makes the image fill the whole square, same as CSS object-fit: cover.
    return Math.max(VIEWPORT_SIZE / naturalWidth, VIEWPORT_SIZE / naturalHeight);
  }

  function onPickFile(file: File | null | undefined) {
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      toast.error("Please choose an image file.");
      return;
    }
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      imageRef.current = img;
      const scale = baseScale(img.naturalWidth, img.naturalHeight);
      setNaturalSize({ w: img.naturalWidth, h: img.naturalHeight });
      setZoom(MIN_ZOOM);
      // Centered starting position - the middle of the picture, not a corner.
      setOffset(
        clampOffset(
          {
            x: (VIEWPORT_SIZE - img.naturalWidth * scale) / 2,
            y: (VIEWPORT_SIZE - img.naturalHeight * scale) / 2,
          },
          scale,
          img.naturalWidth,
          img.naturalHeight
        )
      );
      setImageUrl(url);
    };
    img.onerror = () => {
      toast.error("Could not read that image file.");
      URL.revokeObjectURL(url);
    };
    img.src = url;
  }

  // Redraws the crop canvas on every relevant change (new image, pan, zoom).
  // Reading refs each time rather than closing over stale image dimensions.
  useEffect(() => {
    const canvas = canvasRef.current;
    const img = imageRef.current;
    if (!canvas || !img || !naturalSize) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.clearRect(0, 0, VIEWPORT_SIZE, VIEWPORT_SIZE);
    const scale = baseScale(naturalSize.w, naturalSize.h) * zoom;
    ctx.drawImage(img, offset.x, offset.y, naturalSize.w * scale, naturalSize.h * scale);
  }, [imageUrl, naturalSize, zoom, offset]);

  function onZoomChange(nextZoom: number) {
    if (!naturalSize) return;
    const oldScale = baseScale(naturalSize.w, naturalSize.h) * zoom;
    const newScale = baseScale(naturalSize.w, naturalSize.h) * nextZoom;
    // Zoom around the viewport's current center - the image-space point
    // under the center stays under the center after the scale changes,
    // instead of zoom silently dragging the picture toward a corner.
    const centerImgX = (VIEWPORT_SIZE / 2 - offset.x) / oldScale;
    const centerImgY = (VIEWPORT_SIZE / 2 - offset.y) / oldScale;
    const nextOffset = {
      x: VIEWPORT_SIZE / 2 - centerImgX * newScale,
      y: VIEWPORT_SIZE / 2 - centerImgY * newScale,
    };
    setZoom(nextZoom);
    setOffset(clampOffset(nextOffset, newScale, naturalSize.w, naturalSize.h));
  }

  const onPointerDown = useCallback(
    (e: { target: any; pointerId: number; clientX: number; clientY: number }) => {
      if (!naturalSize) return;
      e.target.setPointerCapture(e.pointerId);
      dragRef.current = { startX: e.clientX, startY: e.clientY, startOffset: offset };
    },
    [offset, naturalSize]
  );

  const onPointerMove = useCallback(
    (e: { clientX: number; clientY: number }) => {
      if (!dragRef.current || !naturalSize) return;
      const scale = baseScale(naturalSize.w, naturalSize.h) * zoom;
      const dx = e.clientX - dragRef.current.startX;
      const dy = e.clientY - dragRef.current.startY;
      setOffset(
        clampOffset(
          { x: dragRef.current.startOffset.x + dx, y: dragRef.current.startOffset.y + dy },
          scale,
          naturalSize.w,
          naturalSize.h
        )
      );
    },
    [zoom, naturalSize]
  );

  function onPointerUp(e: { target: any; pointerId: number }) {
    dragRef.current = null;
    try {
      e.target.releasePointerCapture(e.pointerId);
    } catch {
      // Already released (e.g. pointer left the window mid-drag) - harmless.
    }
  }

  async function onConfirm() {
    const img = imageRef.current;
    if (!img || !naturalSize) return;
    setUploading(true);
    try {
      const scale = baseScale(naturalSize.w, naturalSize.h) * zoom;
      // Inverse of the preview transform: the viewport rect maps back to
      // this source rect in the ORIGINAL image's own pixel coordinates -
      // exporting straight from the source image (not by upscaling the
      // small preview canvas) keeps the result sharp regardless of how
      // zoomed-out the on-screen preview is.
      const sx = -offset.x / scale;
      const sy = -offset.y / scale;
      const sSize = VIEWPORT_SIZE / scale;

      const outputCanvas = document.createElement("canvas");
      outputCanvas.width = OUTPUT_SIZE;
      outputCanvas.height = OUTPUT_SIZE;
      const ctx = outputCanvas.getContext("2d");
      if (!ctx) throw new Error("Canvas is not available in this browser.");
      ctx.drawImage(img, sx, sy, sSize, sSize, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE);

      const blob = await new Promise<Blob | null>((resolve) => outputCanvas.toBlob(resolve, "image/png"));
      if (!blob) throw new Error("Could not export the cropped image.");

      const account = await api.uploadAccountAvatar(blob);
      onUploaded(account);
      onClose();
    } catch (err: any) {
      // uploadAccountAvatar already toasts real API failures (400/500 etc.
      // via toJson) - this catch is for the local canvas-export step above,
      // which never reaches the network.
      if (!err?.status) toast.error(err?.message || "Could not process that image.");
    } finally {
      setUploading(false);
    }
  }

  if (!mounted) return null;

  return createPortal(
    <AnimatePresence>
      {open && (
        <AvatarCropDialog
          onClose={onClose}
          imageUrl={imageUrl}
          fileInputRef={fileInputRef}
          onPickFile={onPickFile}
          canvasRef={canvasRef}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          zoom={zoom}
          onZoomChange={onZoomChange}
          onConfirm={onConfirm}
          uploading={uploading}
        />
      )}
    </AnimatePresence>,
    document.body
  );
}

// Split out so useDialogFocus (initial focus, Tab trap, Escape, focus
// restore) runs on this component's own mount/unmount, which tracks `open`
// exactly - the parent AvatarUploadModal itself stays mounted the whole time
// AppShell is up, so a hook keyed to ITS mount would only ever fire once.
function AvatarCropDialog({
  onClose,
  imageUrl,
  fileInputRef,
  onPickFile,
  canvasRef,
  onPointerDown,
  onPointerMove,
  onPointerUp,
  zoom,
  onZoomChange,
  onConfirm,
  uploading,
}: {
  onClose: () => void;
  imageUrl: string | null;
  fileInputRef: RefObject<HTMLInputElement | null>;
  onPickFile: (file: File | null | undefined) => void;
  canvasRef: RefObject<HTMLCanvasElement | null>;
  onPointerDown: (e: any) => void;
  onPointerMove: (e: any) => void;
  onPointerUp: (e: any) => void;
  zoom: number;
  onZoomChange: (zoom: number) => void;
  onConfirm: () => void;
  uploading: boolean;
}) {
    const tHC = useTranslations("hcComponentsAvatarUploadModal");
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const closeBtnRef = useRef<HTMLButtonElement | null>(null);
  useDialogFocus(dialogRef, closeBtnRef, onClose);

  return (
    <motion.div
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 backdrop-blur-sm"
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: 0.15 }}
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={tHC("s5")}
    >
      <motion.div
        ref={dialogRef}
        className="w-full max-w-sm rounded-xl border border-border bg-card p-6 text-foreground shadow-2xl"
        initial={{ opacity: 0, scale: 0.96, y: 8 }}
        animate={{ opacity: 1, scale: 1, y: 0 }}
        exit={{ opacity: 0, scale: 0.97, y: 6 }}
        transition={{ duration: 0.18, ease: [0.16, 1, 0.3, 1] }}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-base font-semibold tracking-tight">{tHC("s5")}</h2>
          <button
            ref={closeBtnRef}
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="flex size-7 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
          >
            <X aria-hidden="true" size={16} />
          </button>
        </div>

        {!imageUrl ? (
          <div className="flex flex-col items-center gap-3 py-6">
            <div className="flex size-16 items-center justify-center rounded-full bg-primary/10 text-primary">
              <Upload aria-hidden="true" size={22} />
            </div>
            <p className="text-center text-sm text-muted-foreground">
              {tHC("s4")}
            </p>
            <Button type="button" onClick={() => fileInputRef.current?.click()}>
              {tHC("s3")}
            </Button>
            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              className="hidden"
              onChange={(e) => onPickFile(e.target.files?.[0])}
            />
          </div>
        ) : (
          <div className="flex flex-col items-center gap-4">
            <div
              className="overflow-hidden rounded-full border border-border shadow-inner"
              style={{ width: VIEWPORT_SIZE, height: VIEWPORT_SIZE, touchAction: "none" }}
            >
              <canvas
                ref={canvasRef}
                width={VIEWPORT_SIZE}
                height={VIEWPORT_SIZE}
                className="cursor-grab active:cursor-grabbing"
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={onPointerUp}
                onPointerLeave={onPointerUp}
              />
            </div>

            <div className="flex w-full items-center gap-2.5">
              <ZoomIn aria-hidden="true" size={15} className="shrink-0 text-muted-foreground" />
              <input
                type="range"
                min={MIN_ZOOM}
                max={MAX_ZOOM}
                step={0.01}
                value={zoom}
                onChange={(e) => onZoomChange(Number(e.target.value))}
                className="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-secondary accent-primary"
                aria-label="Zoom"
              />
            </div>

            <p className="text-center text-xs text-muted-foreground">{tHC("s2")}</p>

            <div className="flex w-full items-center justify-between gap-2 pt-1">
              <Button type="button" variant="outline" onClick={() => fileInputRef.current?.click()}>
                {tHC("s1")}
              </Button>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/*"
                className="hidden"
                onChange={(e) => onPickFile(e.target.files?.[0])}
              />
              <Button type="button" onClick={onConfirm} disabled={uploading}>
                {uploading ? "Saving…" : "Save"}
              </Button>
            </div>
          </div>
        )}
      </motion.div>
    </motion.div>
  );
}
