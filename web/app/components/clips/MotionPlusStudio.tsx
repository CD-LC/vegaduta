"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { AlertTriangle, ArrowLeft, Clapperboard, Download, Film, Loader2, X } from "lucide-react";
import { api, triggerBrowserDownload } from "../../lib/api";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "../ui/card";
import { Button } from "../ui/button";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { detectCapabilities } from "../../lib/edge/capabilities";
import {
  CAMERA_PATHS,
  MAX_KEYFRAMES,
  MIN_KEYFRAMES,
  generateKeyframes,
  type CameraPath,
  type GeneratedKeyframe,
} from "../../lib/edge/video/keyframes";
import {
  MOTION_FPS,
  buildInterpolatedFrames,
  checkMotionEngineAvailability,
  estimatedDurationSeconds,
  listMotionEngines,
  loadMotionEngine,
  releaseFrames,
  setMotionModelOverride,
  type MotionEngine,
  type MotionEngineAvailability,
  type MotionEngineOption,
} from "../../lib/edge/video/rife";
import { encodeFrameSequence } from "../../lib/edge/video/encode";
import { type ClipRecordingSupport, detectClipRecordingSupport } from "../../lib/edge/video/assemble";
import { useTranslations } from "../../lib/i18n";

/**
 * Clip Studio "Motion+" mode (tier V1 — keyframes + on-device
 * interpolation). Flow: prompt → 3–5 prompt-chained keyframes generated
 * through the EXISTING agent image pipeline (uses the user's normal image
 * quota — stated in-product before generating) → on-device frame
 * interpolation (rife.ts: WebGPU, WASM fallback) → real-time encode
 * (encode.ts over the V0 MediaRecorder pipeline) → preview/download.
 *
 * Availability posture: rendered only when edgeEnabled() (ClipStudio gates
 * the tab). If the interpolation model isn't published in the edge manifest,
 * this whole mode shows one calm note and V0 keeps working untouched. Any
 * failure here surfaces as a quiet inline error — the hosted path and the
 * rest of Clip Studio are never degraded.
 */

const OUTPUT_OPTIONS: Record<string, { label: string; width: number; height: number }> = {
  "640x360": { label: "640 × 360 — Fast", width: 640, height: 360 },
  "960x540": { label: "960 × 540 — Sharper (slower)", width: 960, height: 540 },
};

type Phase = "idle" | "keyframes" | "engine" | "interpolating" | "encoding";

/** allowedToolNames semantics mirror McpPanel.tsx: empty/absent = the agent
 * is unrestricted, so the tool registry's generate_image is reachable. */
function agentCanGenerateImages(agent: any): boolean {
  const allowed = agent?.allowedToolNames;
  if (!allowed || (Array.isArray(allowed) && allowed.length === 0)) return true;
  return Array.isArray(allowed) && allowed.includes("generate_image");
}

interface MotionPlusStudioProps {
  /** ClipStudio's own setMode("still") - see that file's call site comment
   * (live user report, 2026-08-07: stuck on this screen with "not able to
   * go back"). Optional so this component still renders standalone (e.g. in
   * a future embed) without crashing if a caller doesn't pass it - the back
   * button just doesn't render in that case. */
  onBackToStill?: () => void;
}

export default function MotionPlusStudio({ onBackToStill }: MotionPlusStudioProps = {}) {
    const tHC = useTranslations("hcComponentsClipsMotionPlusStudio");
  // --- Environment / availability ----------------------------------------
  const [engines, setEngines] = useState<MotionEngineOption[]>([]);
  const [enginesLoaded, setEnginesLoaded] = useState(false);
  const [engineId, setEngineId] = useState<string>("");
  const [availability, setAvailability] = useState<MotionEngineAvailability | null>(null);
  const [support, setSupport] = useState<ClipRecordingSupport | null>(null);

  // --- Inputs --------------------------------------------------------------
  const [agents, setAgents] = useState<any[]>([]);
  const [agentsLoaded, setAgentsLoaded] = useState(false);
  const [agentId, setAgentId] = useState<string>("");
  const [prompt, setPrompt] = useState("");
  const [frameCount, setFrameCount] = useState(4);
  const [cameraPath, setCameraPath] = useState<CameraPath>("drift-right");
  const [outputKey, setOutputKey] = useState("640x360");

  // --- Run state -----------------------------------------------------------
  const [phase, setPhase] = useState<Phase>("idle");
  const [phaseDetail, setPhaseDetail] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [keyframePreviews, setKeyframePreviews] = useState<Array<{ imageId: string; url: string }>>([]);
  const [result, setResult] = useState<{ url: string; blob: Blob; extension: string } | null>(null);

  const recordCanvasRef = useRef<HTMLCanvasElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    setSupport(detectClipRecordingSupport());
    let cancelled = false;
    void listMotionEngines().then((options) => {
      if (cancelled) return;
      setEngines(options);
      setEnginesLoaded(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Resolves which engine is actually in play once the manifest has loaded,
  // and again whenever the person picks a different one from the selector
  // below. Kept as its own effect (rather than derived synchronously from
  // `engines`) so the "isn't published yet" / auto-pick logic stays in one
  // place — checkMotionEngineAvailability in rife.ts — instead of being
  // duplicated here.
  useEffect(() => {
    if (!enginesLoaded) return undefined;
    let cancelled = false;
    void checkMotionEngineAvailability(engineId || undefined).then((a) => {
      if (cancelled) return;
      setAvailability(a);
      if (a.id && a.id !== engineId) setEngineId(a.id);
    });
    return () => {
      cancelled = true;
    };
  }, [enginesLoaded, engineId]);

  function handleEngineChange(id: string) {
    setMotionModelOverride(id);
    setEngineId(id);
  }

  useEffect(() => {
    let cancelled = false;
    api
      .listAgents()
      .then((rows: any) => {
        if (cancelled) return;
        const list = Array.isArray(rows) ? rows : [];
        // Image-capable agents first — they're the ones that can actually
        // fulfil the keyframe turns.
        const sorted = [...list].sort((a, b) => Number(agentCanGenerateImages(b)) - Number(agentCanGenerateImages(a)));
        setAgents(sorted);
        const firstCapable = sorted.find(agentCanGenerateImages);
        if (firstCapable?.id != null) setAgentId(String(firstCapable.id));
      })
      .catch(() => {
        // Calm: the picker just shows the empty-state note.
      })
      .finally(() => {
        if (!cancelled) setAgentsLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const releasePreviews = useCallback((previews: Array<{ imageId: string; url: string }>) => {
    for (const p of previews) URL.revokeObjectURL(p.url);
  }, []);

  async function handleCreate() {
    const canvas = recordCanvasRef.current;
    if (!canvas || !availability?.available || phase !== "idle") return;
    if (!prompt.trim() || !agentId) return;
    if (typeof createImageBitmap !== "function") {
      setError("This browser can't decode frames for interpolation (createImageBitmap is missing).");
      return;
    }

    setError(null);
    setResult((prev) => {
      if (prev) URL.revokeObjectURL(prev.url);
      return null;
    });
    setKeyframePreviews((prev) => {
      releasePreviews(prev);
      return [];
    });

    const controller = new AbortController();
    abortRef.current = controller;
    const output = OUTPUT_OPTIONS[outputKey] || OUTPUT_OPTIONS["640x360"];
    let engine: MotionEngine | null = null;
    let keyframeBitmaps: ImageBitmap[] = [];
    let frames: ImageBitmap[] | null = null;

    try {
      // 1 — keyframes through the existing image pipeline (user's quota).
      setPhase("keyframes");
      setPhaseDetail(`Generating keyframes 1/${frameCount}`);
      const keyframes: GeneratedKeyframe[] = await generateKeyframes({
        agentId,
        scenePrompt: prompt,
        frameCount,
        cameraPath,
        onProgress: (n, total) => setPhaseDetail(`Generating keyframes ${n}/${total}`),
        signal: controller.signal,
      });
      setKeyframePreviews(keyframes.map((k) => ({ imageId: k.imageId, url: URL.createObjectURL(k.blob) })));
      keyframeBitmaps = await Promise.all(keyframes.map((k) => createImageBitmap(k.blob)));

      // 2 — the on-device motion engine (cached after first download).
      setPhase("engine");
      setPhaseDetail("Preparing motion engine…");
      const caps = await detectCapabilities();
      engine = await loadMotionEngine({
        availability,
        preferWebGpu: caps.webgpu.available,
        onDownloadProgress: (received, total) =>
          setPhaseDetail(
            total > 0
              ? `Downloading motion engine ${Math.min(99, Math.round((received / total) * 100))}%`
              : "Downloading motion engine…"
          ),
        signal: controller.signal,
      });

      // 3 — interpolate to 24fps.
      setPhase("interpolating");
      setPhaseDetail("Interpolating 0%");
      frames = await buildInterpolatedFrames({
        engine,
        keyframes: keyframeBitmaps,
        width: output.width,
        height: output.height,
        onProgress: (p) => setPhaseDetail(`Interpolating ${p}%`),
        signal: controller.signal,
      });
      for (const bitmap of keyframeBitmaps) bitmap.close();
      keyframeBitmaps = [];

      // 4 — encode (real-time playback over the V0 recorder — tab visible).
      setPhase("encoding");
      setPhaseDetail("Encoding 0% — keep this tab visible");
      const clip = await encodeFrameSequence({
        frames,
        fps: MOTION_FPS,
        width: output.width,
        height: output.height,
        canvas,
        onProgress: (p) => setPhaseDetail(`Encoding ${p}% — keep this tab visible`),
        signal: controller.signal,
      });
      setResult({ url: URL.createObjectURL(clip.blob), blob: clip.blob, extension: clip.extension });
    } catch (err: any) {
      if (err?.name !== "AbortError") {
        setError(err?.message || "Motion+ couldn't finish this clip.");
      }
    } finally {
      releaseFrames(frames);
      for (const bitmap of keyframeBitmaps) {
        try {
          bitmap.close();
        } catch {
          // Already closed.
        }
      }
      engine?.dispose();
      abortRef.current = null;
      setPhase("idle");
      setPhaseDetail("");
    }
  }

  function handleCancel() {
    abortRef.current?.abort();
  }

  function handleDownload() {
    if (!result) return;
    triggerBrowserDownload(result.blob, `vegaduta-motionplus-${Date.now()}.${result.extension}`);
  }

  // Cleanup: abort in-flight work, revoke object URLs.
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
    const current = keyframePreviews;
    return () => releasePreviews(current);
  }, [keyframePreviews, releasePreviews]);

  const busy = phase !== "idle";
  const unsupported = support !== null && !support.supported;
  const checking = availability === null;
  const engineMissing = availability !== null && !availability.available;
  const capableAgents = agents.filter(agentCanGenerateImages);
  const canCreate = !busy && !unsupported && !!availability?.available && !!prompt.trim() && !!agentId;
  // Several independent reasons can disable Create — say which one applies
  // instead of leaving the button silently inert (only "unsupported browser"
  // used to get its own Alert above; the rest had no explanation at all).
  const createDisabledReason =
    !busy && !canCreate
      ? unsupported
        ? null // already explained by the Alert above
        : !agentId
          ? "Select an agent above first."
          : !prompt.trim()
            ? "Describe the scene above first."
            : !availability?.available
              ? "Motion+ isn't ready yet."
              : null
      : null;
  const durationSec = estimatedDurationSeconds(frameCount);

  // ---- Calm not-published state (V0 keeps working in the other tab) -------
  // Both branches below fully replace this component's tree (no tab bar of
  // their own), so each carries its own explicit way back rather than
  // depending entirely on ClipStudio's outer tab bar being noticed/reachable
  // (live user report, 2026-08-07: stuck here with "not able to go back").
  if (checking) {
    return (
      <div className="space-y-3">
        <div className="flex items-center gap-2 py-8 text-sm text-muted-foreground">
          <Loader2 size={14} className="animate-spin" aria-hidden="true" /> {tHC("s24")}
        </div>
        {onBackToStill && (
          <Button variant="outline" size="sm" onClick={onBackToStill} className="gap-1.5">
            <ArrowLeft size={13} aria-hidden="true" /> {tHC("s21")}
          </Button>
        )}
      </div>
    );
  }
  if (engineMissing) {
    return (
      <Card>
        <CardContent className="flex flex-col items-center gap-2 py-10 text-center">
          <Film size={22} className="text-muted-foreground" aria-hidden="true" />
          <p className="text-sm font-medium text-foreground">
            {availability?.displayName || "Motion+ interpolation model"} {tHC("s23")}
          </p>
          <p className="max-w-md text-sm text-muted-foreground">
            {tHC("s22")}
          </p>
          {onBackToStill && (
            <Button variant="outline" size="sm" onClick={onBackToStill} className="mt-2 gap-1.5">
              <ArrowLeft size={13} aria-hidden="true" /> {tHC("s21")}
            </Button>
          )}
        </CardContent>
      </Card>
    );
  }

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      {/* ------------------------- Setup column ------------------------- */}
      <div className="space-y-6">
        <Card>
          <CardHeader>
            <CardTitle>
              <h2 style={{ font: "inherit", letterSpacing: "inherit", margin: 0 }}>{tHC("s20")}</h2>
            </CardTitle>
            <CardDescription>
              {tHC("s19")} {frameCount} {tHC("s18")}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground" htmlFor="motionplus-agent">
                Agent
              </label>
              <select
                id="motionplus-agent"
                value={agentId}
                disabled={busy}
                onChange={(e) => setAgentId(e.target.value)}
                className="h-8 w-full rounded-md border border-input bg-transparent px-2 text-sm text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {!agentId && <option value="">{tHC("s17")}</option>}
                {agents.map((agent: any) => (
                  <option key={agent.id} value={String(agent.id)}>
                    {agent.name || `Agent ${agent.id}`}
                    {agentCanGenerateImages(agent) ? "" : " — needs the generate_image tool"}
                  </option>
                ))}
              </select>
              {agentsLoaded && agents.length === 0 && (
                <p className="mt-1 text-xs text-muted-foreground">
                  {tHC("s16")}{" "}
                  <Link href="/agents" className="font-medium text-primary underline underline-offset-2">
                    Agents
                  </Link>{" "}
                  {tHC("s15")}
                </p>
              )}
              {agentsLoaded && agents.length > 0 && capableAgents.length === 0 && (
                <p className="mt-1 text-xs text-muted-foreground">
                  {tHC("s14")}
                </p>
              )}
            </div>

            <div>
              <label className="mb-1 block text-sm font-medium text-foreground" htmlFor="motionplus-prompt">
                Scene
              </label>
              <textarea
                id="motionplus-prompt"
                value={prompt}
                disabled={busy}
                onChange={(e) => setPrompt(e.target.value)}
                rows={3}
                placeholder={tHC("s13")}
                className="w-full rounded-md border border-input bg-transparent px-2 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50"
              />
            </div>

            <div className="flex flex-wrap items-end gap-6">
              <div>
                <div className="mb-1 text-sm font-medium text-foreground" id="motionplus-keyframes-label">
                  Keyframes
                </div>
                <div className="flex gap-1" role="group" aria-labelledby="motionplus-keyframes-label">
                  {Array.from({ length: MAX_KEYFRAMES - MIN_KEYFRAMES + 1 }, (_, i) => MIN_KEYFRAMES + i).map(
                    (value) => (
                      <Button
                        key={value}
                        aria-pressed={frameCount === value}
                        variant={frameCount === value ? "secondary" : "outline"}
                        size="sm"
                        className="h-7 px-3 text-xs"
                        disabled={busy}
                        onClick={() => setFrameCount(value)}
                      >
                        {value}
                      </Button>
                    )
                  )}
                </div>
              </div>
              <div>
                <div className="mb-1 text-sm font-medium text-foreground" id="motionplus-camera-label">
                  Camera
                </div>
                <div className="flex gap-1" role="group" aria-labelledby="motionplus-camera-label">
                  {CAMERA_PATHS.map((path) => (
                    <Button
                      key={path.id}
                      aria-pressed={cameraPath === path.id}
                      variant={cameraPath === path.id ? "secondary" : "outline"}
                      size="sm"
                      className="h-7 px-3 text-xs"
                      disabled={busy}
                      title={path.description}
                      onClick={() => setCameraPath(path.id)}
                    >
                      {path.label}
                    </Button>
                  ))}
                </div>
              </div>
              {engines.length > 1 && (
                <div>
                  <div className="mb-1 text-sm font-medium text-foreground" id="motionplus-engine-label">
                    Engine
                  </div>
                  <div className="flex gap-1" role="group" aria-labelledby="motionplus-engine-label">
                    {engines.map((engine) => (
                      <Button
                        key={engine.id}
                        aria-pressed={engineId === engine.id}
                        variant={engineId === engine.id ? "secondary" : "outline"}
                        size="sm"
                        className="h-7 px-3 text-xs"
                        disabled={busy}
                        title={engine.detailName || engine.displayName}
                        onClick={() => handleEngineChange(engine.id)}
                      >
                        {engine.displayName.replace(/^Clip Studio motion engine\s*-?\s*/i, "") || engine.displayName}
                      </Button>
                    ))}
                  </div>
                </div>
              )}
            </div>

            <div className="min-w-48">
              <label className="mb-1 block text-sm font-medium text-foreground" htmlFor="motionplus-output">
                {tHC("s12")}
              </label>
              <select
                id="motionplus-output"
                value={outputKey}
                disabled={busy}
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

            {/* Honest cost + ceiling, stated BEFORE anything is generated. */}
            <div className="rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
              <p>
                Uses <span className="font-medium text-foreground">{frameCount} {tHC("s11")}</span> {tHC("s10")}
              </p>
              <p className="mt-1">
                {tHC("s9")}{durationSec.toFixed(1)}{tHC("s8")} {MOTION_FPS} {tHC("s7")}{" "}
                {availability?.detailName || "a small open frame-interpolation model"} {tHC("s6")}
              </p>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* ------------------------ Generate / preview column ------------------------ */}
      <div className="space-y-6">
        <Card>
          <CardHeader>
            <CardTitle>
              <h2 style={{ font: "inherit", letterSpacing: "inherit", margin: 0 }}>{tHC("s5")}</h2>
            </CardTitle>
            <CardDescription>{tHC("s4")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {unsupported && (
              <Alert variant="destructive">
                <AlertTriangle aria-hidden="true" />
                <AlertTitle>{tHC("s3")}</AlertTitle>
                <AlertDescription>{support?.reason}</AlertDescription>
              </Alert>
            )}

            <div className="flex flex-wrap items-center gap-2">
              <Button onClick={handleCreate} disabled={!canCreate} className="gap-1.5">
                {busy ? (
                  <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                ) : (
                  <Clapperboard size={14} aria-hidden="true" />
                )}
                {busy ? "Working…" : "Create Motion+ clip"}
              </Button>
              {busy && (
                <Button variant="outline" size="sm" onClick={handleCancel} className="gap-1">
                  <X size={13} aria-hidden="true" /> Cancel
                </Button>
              )}
            </div>
            {createDisabledReason && <p className="text-xs text-muted-foreground">{createDisabledReason}</p>}

            {busy && (
              <p className="text-xs text-muted-foreground" role="status">
                {phaseDetail || "Working…"}
                {phase === "keyframes" && " — each keyframe is one normal image generation."}
              </p>
            )}

            {error && (
              <Alert variant="destructive">
                <AlertTriangle aria-hidden="true" />
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}

            {keyframePreviews.length > 0 && (
              <div>
                <div className="mb-1 text-xs font-medium text-muted-foreground">Keyframes</div>
                <div className="flex flex-wrap gap-1.5">
                  {keyframePreviews.map((k, index) => (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      key={k.imageId}
                      src={k.url}
                      alt={`Keyframe ${index + 1}`}
                      className="h-14 w-auto rounded border border-border bg-black object-cover"
                    />
                  ))}
                </div>
              </div>
            )}

            {result && (
              <div className="space-y-2">
                <video controls src={result.url} className="h-auto w-full rounded-md border border-border bg-black" />
                <Button variant="outline" size="sm" onClick={handleDownload} className="gap-1.5">
                  <Download size={13} aria-hidden="true" /> {tHC("s2")}{result.extension}
                </Button>
              </div>
            )}

            <p className="text-xs text-muted-foreground">
              {tHC("s1")}
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Off-screen (but painted) canvas the encoder records from — same
          rationale as ClipStudio's V0 canvas: display:none would starve
          captureStream frame delivery in some engines. */}
      <canvas
        ref={recordCanvasRef}
        aria-hidden="true"
        className="pointer-events-none fixed top-0 h-px w-px opacity-0"
        style={{ left: -10000 }}
      />
    </div>
  );
}
