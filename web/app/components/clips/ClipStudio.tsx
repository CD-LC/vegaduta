"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { AlertTriangle, Clapperboard, Download, ImageIcon, Loader2, Upload, X } from "lucide-react";
import { api, triggerBrowserDownload } from "../../lib/api";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "../ui/card";
import { Button } from "../ui/button";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { usePageReady } from "../RouteTransition";
import MotionPresetPicker from "./MotionPresetPicker";
import MotionPlusStudio from "./MotionPlusStudio";
import { edgeEnabled } from "../../lib/edge/capabilities";
import {
  type KenBurnsSource,
  type MotionPreset,
  createKenBurnsRenderer,
  loadImageSource,
  releaseImageSource,
} from "../../lib/edge/video/kenburns";
import { type ClipRecordingSupport, detectClipRecordingSupport, recordClip } from "../../lib/edge/video/assemble";

/**
 * Clip Studio (tier V0 — Ken Burns clip maker). Turns a still
 * image (a tenant-generated image or a local upload that never leaves the
 * browser) into a short pan/zoom video, rendered and encoded entirely
 * on-device via canvas.captureStream + MediaRecorder (assemble.ts). No new
 * backend surface: the only server calls are the EXISTING generated-image
 * list/download endpoints.
 */

const OUTPUT_OPTIONS: Record<string, { label: string; width: number; height: number }> = {
  "1280x720": { label: "1280 × 720 — Landscape HD", width: 1280, height: 720 },
  "720x1280": { label: "720 × 1280 — Portrait", width: 720, height: 1280 },
  "1080x1080": { label: "1080 × 1080 — Square", width: 1080, height: 1080 },
  "640x360": { label: "640 × 360 — Small & fast", width: 640, height: 360 },
};

function truncate(text: string, max: number) {
  if (!text) return text;
  return text.length > max ? text.slice(0, max).trimEnd() + "…" : text;
}

export default function ClipStudio() {
  const searchParams = useSearchParams();
  const preloadImageId = searchParams.get("imageId");

  // Tier V1 ("Motion+"): a second, flag-gated mode alongside the
  // V0 Ken Burns flow. edgeEnabled() is a build-time constant, so the tab bar
  // either exists for the whole session or not at all — with the flag off,
  // this component renders exactly the V0 surface it always did.
  const motionPlusEnabled = edgeEnabled();
  const [mode, setMode] = useState<"still" | "motion">("still");

  // --- Image source state -------------------------------------------------
  const [images, setImages] = useState<any[]>([]);
  const [imagesLoaded, setImagesLoaded] = useState(false);
  const [imagesFailed, setImagesFailed] = useState(false);
  const [source, setSource] = useState<KenBurnsSource | null>(null);
  const [sourceLabel, setSourceLabel] = useState("");
  const [selectedImageId, setSelectedImageId] = useState<any>(null);
  const [loadingImageId, setLoadingImageId] = useState<any>(null);
  const [sourceError, setSourceError] = useState<string | null>(null);

  // --- Motion / output controls -------------------------------------------
  const [preset, setPreset] = useState<MotionPreset>("zoom-in");
  const [durationSec, setDurationSec] = useState(6);
  const [fps, setFps] = useState<24 | 30>(30);
  const [outputKey, setOutputKey] = useState("1280x720");

  // --- Recording state -----------------------------------------------------
  const [support, setSupport] = useState<ClipRecordingSupport | null>(null);
  const [recording, setRecording] = useState(false);
  const [progress, setProgress] = useState(0);
  const [recordError, setRecordError] = useState<string | null>(null);
  const [result, setResult] = useState<{ url: string; blob: Blob; extension: string } | null>(null);

  const previewCanvasRef = useRef<HTMLCanvasElement>(null);
  const recordCanvasRef = useRef<HTMLCanvasElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const preloadedRef = useRef(false);

  usePageReady(imagesLoaded);

  // Capability detection is browser-only — run once on the client.
  useEffect(() => {
    setSupport(detectClipRecordingSupport());
  }, []);

  useEffect(() => {
    api
      .listGeneratedImages()
      .then((rows: any) => setImages(rows || []))
      .catch(() => setImagesFailed(true))
      .finally(() => setImagesLoaded(true));
  }, []);

  const adoptSource = useCallback((next: KenBurnsSource, label: string, imageId: any) => {
    setSource((prev) => {
      if (prev && prev !== next) releaseImageSource(prev);
      return next;
    });
    setSourceLabel(label);
    setSelectedImageId(imageId);
    setSourceError(null);
  }, []);

  const loadGeneratedImage = useCallback(
    async (imageId: any, label: string) => {
      setSourceError(null);
      setLoadingImageId(imageId);
      try {
        const { blob } = await api.downloadGeneratedImage(imageId);
        const bitmap = await loadImageSource(blob);
        adoptSource(bitmap, label, imageId);
      } catch (err: any) {
        setSourceError(err?.message || "Couldn't load that image.");
      } finally {
        setLoadingImageId(null);
      }
    },
    [adoptSource]
  );

  // Deep link from the Files page: /clips?imageId=<id> preloads that image.
  useEffect(() => {
    if (!preloadImageId || preloadedRef.current) return;
    preloadedRef.current = true;
    void loadGeneratedImage(preloadImageId, "Generated image");
  }, [preloadImageId, loadGeneratedImage]);

  // Once the list arrives, upgrade the preloaded image's generic label to its
  // real prompt (purely cosmetic — never blocks the preload).
  useEffect(() => {
    if (!preloadImageId || !imagesLoaded) return;
    const row = (images || []).find((r: any) => String(r.id) === String(preloadImageId));
    if (row) {
      setSourceLabel((prev) => (prev === "Generated image" ? truncate(row.prompt, 60) || prev : prev));
    }
  }, [preloadImageId, imagesLoaded, images]);

  async function handleUpload(file: File | null | undefined) {
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setSourceError("That file isn't an image. Pick a PNG, JPEG, or WebP.");
      return;
    }
    setSourceError(null);
    try {
      const bitmap = await loadImageSource(file);
      adoptSource(bitmap, file.name, null);
    } catch (err: any) {
      setSourceError(err?.message || "Couldn't read that image file.");
    }
  }

  // --- Live looping preview ------------------------------------------------
  const output = OUTPUT_OPTIONS[outputKey] || OUTPUT_OPTIONS["1280x720"];
  useEffect(() => {
    const canvas = previewCanvasRef.current;
    if (!canvas || !source) return undefined;

    // Preview renders at a reduced resolution with the output's aspect ratio
    // — the recording pass re-renders at full size on its own canvas.
    const scale = 480 / Math.max(output.width, output.height);
    const previewSize = {
      width: Math.max(2, Math.round(output.width * scale)),
      height: Math.max(2, Math.round(output.height * scale)),
    };
    canvas.width = previewSize.width;
    canvas.height = previewSize.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) return undefined;

    let renderer;
    try {
      renderer = createKenBurnsRenderer(source, { preset }, previewSize);
    } catch {
      return undefined;
    }

    const durationMs = durationSec * 1000;
    const holdMs = 700; // brief hold on the final frame before looping
    const startedAt = performance.now();
    let raf = 0;
    const loop = () => {
      const t = ((performance.now() - startedAt) % (durationMs + holdMs)) / durationMs;
      renderer.drawFrame(ctx, Math.min(1, t));
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [source, preset, durationSec, output.width, output.height]);

  // --- Recording -----------------------------------------------------------
  function handleCreate() {
    const canvas = recordCanvasRef.current;
    if (!canvas || !source) return;
    setRecordError(null);
    setProgress(0);
    canvas.width = output.width;
    canvas.height = output.height;
    const ctx = canvas.getContext("2d");
    if (!ctx) {
      setRecordError("Couldn't get a drawing context for the recording canvas.");
      return;
    }
    let renderer;
    try {
      renderer = createKenBurnsRenderer(source, { preset }, { width: output.width, height: output.height });
    } catch (err: any) {
      setRecordError(err?.message || "Couldn't prepare the animation.");
      return;
    }
    const controller = new AbortController();
    abortRef.current = controller;
    setRecording(true);
    recordClip({
      canvas,
      drawFrame: (p) => renderer.drawFrame(ctx, p),
      durationMs: durationSec * 1000,
      fps,
      onProgress: setProgress,
      signal: controller.signal,
    })
      .then((clip) => {
        setResult((prev) => {
          if (prev) URL.revokeObjectURL(prev.url);
          return { url: URL.createObjectURL(clip.blob), blob: clip.blob, extension: clip.extension };
        });
      })
      .catch((err: any) => {
        if (err?.name !== "AbortError") {
          setRecordError(err?.message || "Recording failed.");
        }
      })
      .finally(() => {
        setRecording(false);
        abortRef.current = null;
      });
  }

  function handleCancel() {
    abortRef.current?.abort();
  }

  function handleDownload() {
    if (!result) return;
    triggerBrowserDownload(result.blob, `vegaduta-clip-${Date.now()}.${result.extension}`);
  }

  // Cleanup: revoke blob URLs, close bitmaps, abort any in-flight recording.
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);
  useEffect(() => {
    const current = result;
    return () => {
      if (current) URL.revokeObjectURL(current.url);
    };
  }, [result]);
  useEffect(() => {
    const current = source;
    return () => releaseImageSource(current);
  }, [source]);

  const unsupported = support !== null && !support.supported;
  const canCreate = !!source && !recording && !unsupported;

  return (
    <div className="space-y-6">
      {motionPlusEnabled && (
        <div
          className="flex w-fit gap-1 rounded-lg border border-border bg-muted/40 p-1"
          role="group"
          aria-label="Clip Studio mode"
        >
          <Button
            aria-pressed={mode === "still"}
            variant={mode === "still" ? "secondary" : "ghost"}
            size="sm"
            className="h-7 px-3 text-xs"
            disabled={recording}
            onClick={() => setMode("still")}
          >
            Still clip
          </Button>
          <Button
            aria-pressed={mode === "motion"}
            variant={mode === "motion" ? "secondary" : "ghost"}
            size="sm"
            className="h-7 px-3 text-xs"
            disabled={recording}
            onClick={() => setMode("motion")}
          >
            Motion+
          </Button>
        </div>
      )}

      {motionPlusEnabled && mode === "motion" ? (
        // Live user report, 2026-08-07: "selected [Motion+] and not able to
        // go back" - the tab bar above is the only way back to Still clip,
        // and clearly wasn't a reliable enough affordance on its own
        // (missed, scrolled past, or just not registering as related to
        // this fully-separate-looking card). onBackToStill gives
        // MotionPlusStudio's own dead-end states (not-published, no
        // WebGPU/canvas support) an explicit, self-contained way back
        // instead of relying solely on this external tab bar.
        <MotionPlusStudio onBackToStill={() => setMode("still")} />
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          {/* ------------------------- Source column ------------------------- */}
          <div className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle>
                  <h2 style={{ font: "inherit", letterSpacing: "inherit", margin: 0 }}>1 · Pick an image</h2>
                </CardTitle>
                <CardDescription>
                  Use one of your generated images, or upload a photo — uploads never leave your browser.
                </CardDescription>
              </CardHeader>
              <CardContent>
                {sourceError && (
                  <Alert variant="destructive" className="mb-4">
                    <AlertTriangle aria-hidden="true" />
                    <AlertDescription>{sourceError}</AlertDescription>
                  </Alert>
                )}

                {!imagesLoaded ? (
                  <div className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
                    <Loader2 size={14} className="animate-spin" aria-hidden="true" /> Loading your generated images…
                  </div>
                ) : imagesFailed ? (
                  <p className="py-2 text-sm text-muted-foreground">
                    Couldn&apos;t load your generated images just now — you can still upload an image below.
                  </p>
                ) : images.length === 0 ? (
                  <p className="py-2 text-sm text-muted-foreground">
                    No generated images yet. Ask an agent to create one in the{" "}
                    <Link href="/playground" className="font-medium text-primary underline underline-offset-2">
                      Playground
                    </Link>
                    , or upload an image below.
                  </p>
                ) : (
                  <ul className="max-h-72 space-y-1 overflow-y-auto pr-1">
                    {images.map((row: any) => {
                      const selected = selectedImageId != null && String(selectedImageId) === String(row.id);
                      return (
                        <li
                          key={row.id}
                          className={
                            "flex items-center justify-between gap-3 rounded-md border px-3 py-2 " +
                            (selected ? "border-primary bg-primary/5" : "border-transparent hover:bg-muted/50")
                          }
                        >
                          <div className="flex min-w-0 items-center gap-2">
                            <ImageIcon size={15} className="shrink-0 text-muted-foreground" aria-hidden="true" />
                            <div className="min-w-0">
                              <div className="truncate text-sm text-foreground" title={row.prompt}>
                                {truncate(row.prompt, 60) || "Generated image"}
                              </div>
                              <div className="truncate text-xs text-muted-foreground">
                                {[row.size, row.createdAt ? new Date(row.createdAt).toLocaleDateString() : null]
                                  .filter(Boolean)
                                  .join(" · ")}
                              </div>
                            </div>
                          </div>
                          <Button
                            variant={selected ? "secondary" : "outline"}
                            size="sm"
                            className="h-7 shrink-0 px-2 text-xs"
                            disabled={loadingImageId === row.id}
                            aria-label={`${selected ? "Selected" : "Use"}: ${truncate(row.prompt, 60) || "Generated image"}`}
                            onClick={() => loadGeneratedImage(row.id, truncate(row.prompt, 60) || "Generated image")}
                          >
                            {loadingImageId === row.id ? (
                              <>
                                <Loader2 size={12} className="animate-spin" aria-hidden="true" />
                                <span className="sr-only">Loading…</span>
                              </>
                            ) : selected ? (
                              "Selected"
                            ) : (
                              "Use"
                            )}
                          </Button>
                        </li>
                      );
                    })}
                  </ul>
                )}

                <div className="mt-4 border-t border-border pt-4">
                  <label className="inline-flex cursor-pointer items-center gap-2 rounded-md text-sm font-medium text-primary has-[:focus-visible]:outline has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-[var(--ring)]">
                    <Upload size={14} aria-hidden="true" />
                    Upload an image from this device
                    <input
                      type="file"
                      accept="image/*"
                      className="sr-only"
                      onChange={(e) => {
                        void handleUpload(e.target.files?.[0]);
                        e.target.value = "";
                      }}
                    />
                  </label>
                  <p className="mt-1 text-xs text-muted-foreground">
                    Stays on your device — it is read locally and never uploaded.
                  </p>
                </div>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle>
                  <h2 style={{ font: "inherit", letterSpacing: "inherit", margin: 0 }}>2 · Motion</h2>
                </CardTitle>
                <CardDescription>Pick a camera move — the preview loops live below.</CardDescription>
              </CardHeader>
              <CardContent className="space-y-5">
                <MotionPresetPicker value={preset} onChange={setPreset} disabled={!source} />

                <div>
                  <div className="mb-1 flex items-center justify-between text-sm">
                    <span className="font-medium text-foreground">Duration</span>
                    <span className="text-muted-foreground">{durationSec}s</span>
                  </div>
                  <input
                    type="range"
                    min={3}
                    max={10}
                    step={1}
                    value={durationSec}
                    disabled={!source}
                    onChange={(e) => setDurationSec(Number(e.target.value))}
                    className="w-full accent-[var(--primary,#6366f1)]"
                    aria-label="Clip duration in seconds"
                  />
                </div>

                <div className="flex flex-wrap items-end gap-6">
                  <div>
                    <div className="mb-1 text-sm font-medium text-foreground" id="clip-fps-label">
                      Frame rate
                    </div>
                    <div className="flex gap-1" role="group" aria-labelledby="clip-fps-label">
                      {([24, 30] as const).map((value) => (
                        <Button
                          key={value}
                          aria-pressed={fps === value}
                          variant={fps === value ? "secondary" : "outline"}
                          size="sm"
                          className="h-7 px-3 text-xs"
                          disabled={!source}
                          onClick={() => setFps(value)}
                        >
                          {value} fps
                        </Button>
                      ))}
                    </div>
                  </div>
                  <div className="min-w-48 flex-1">
                    <label className="mb-1 block text-sm font-medium text-foreground" htmlFor="clip-output-size">
                      Output size
                    </label>
                    <select
                      id="clip-output-size"
                      value={outputKey}
                      disabled={!source}
                      onChange={(e) => setOutputKey(e.target.value)}
                      className="h-8 w-full rounded-md border border-input bg-transparent px-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {Object.entries(OUTPUT_OPTIONS).map(([key, option]) => (
                        <option key={key} value={key}>
                          {option.label}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>
              </CardContent>
            </Card>
          </div>

          {/* ------------------------ Preview / render column ------------------------ */}
          <div className="space-y-6">
            <Card>
              <CardHeader>
                <CardTitle>
                  <h2 style={{ font: "inherit", letterSpacing: "inherit", margin: 0 }}>3 · Preview &amp; create</h2>
                </CardTitle>
                <CardDescription>
                  {source ? (
                    <>
                      Previewing <span className="font-medium text-foreground">{sourceLabel}</span>
                    </>
                  ) : (
                    "The live preview appears here once you pick an image."
                  )}
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                {unsupported && (
                  <Alert variant="destructive">
                    <AlertTriangle aria-hidden="true" />
                    <AlertTitle>This browser can&apos;t record clips</AlertTitle>
                    <AlertDescription>{support?.reason}</AlertDescription>
                  </Alert>
                )}

                {source ? (
                  <div className="mx-auto" style={{ maxWidth: output.width >= output.height ? "100%" : 300 }}>
                    <canvas ref={previewCanvasRef} className="h-auto w-full rounded-md border border-border bg-black" />
                  </div>
                ) : (
                  <div className="flex h-48 items-center justify-center rounded-md border border-dashed border-border text-sm text-muted-foreground">
                    No image selected yet
                  </div>
                )}

                <div className="flex flex-wrap items-center gap-2">
                  <Button onClick={handleCreate} disabled={!canCreate} className="gap-1.5">
                    {recording ? (
                      <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                    ) : (
                      <Clapperboard size={14} aria-hidden="true" />
                    )}
                    {recording ? "Creating…" : "Create clip"}
                  </Button>
                  {recording && (
                    <Button variant="outline" size="sm" onClick={handleCancel} className="gap-1">
                      <X size={13} aria-hidden="true" /> Cancel
                    </Button>
                  )}
                </div>

                {recording && (
                  <div>
                    <div
                      className="h-2 w-full overflow-hidden rounded-full bg-muted"
                      role="progressbar"
                      aria-label="Clip recording progress"
                      aria-valuemin={0}
                      aria-valuemax={100}
                      aria-valuenow={progress}
                    >
                      <div
                        className="h-full rounded-full bg-primary transition-[width] duration-150"
                        style={{ width: `${progress}%` }}
                      />
                    </div>
                    <p className="mt-1.5 text-xs text-muted-foreground" role="status">
                      Recording {progress}% — the clip records in real time ({durationSec}s). Keep this tab visible:
                      browsers pause canvas capture in hidden tabs. The rest of the page stays usable.
                    </p>
                  </div>
                )}

                {recordError && (
                  <Alert variant="destructive">
                    <AlertTriangle aria-hidden="true" />
                    <AlertDescription>{recordError}</AlertDescription>
                  </Alert>
                )}

                {result && (
                  <div className="space-y-2">
                    <video
                      controls
                      src={result.url}
                      className="h-auto w-full rounded-md border border-border bg-black"
                    />
                    <Button variant="outline" size="sm" onClick={handleDownload} className="gap-1.5">
                      <Download size={13} aria-hidden="true" /> Download .{result.extension}
                    </Button>
                  </div>
                )}

                <p className="text-xs text-muted-foreground">
                  Clips are made entirely on your device — free, private, and at no server cost. On-device clips are
                  stylised motion from a still image; cinematic AI video generation is a separate paid feature.
                </p>
              </CardContent>
            </Card>
          </div>

          {/* Off-screen (but painted) canvas the recorder captures from. Kept in
          the DOM because some engines only deliver captureStream frames for
          canvases that are actually rendered; display:none would starve it. */}
          <canvas
            ref={recordCanvasRef}
            aria-hidden="true"
            className="pointer-events-none fixed top-0 h-px w-px opacity-0"
            style={{ left: -10000 }}
          />
        </div>
      )}
    </div>
  );
}
