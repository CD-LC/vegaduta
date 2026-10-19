"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Camera, Check, RefreshCw, RotateCcw, SwitchCamera } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import LiveDot from "../agents/camera/LiveDot";
import AssistantOverlay from "./AssistantOverlay";

const MAX_EDGE = 1600;

type Stage = "starting" | "live" | "review" | "unavailable";

/** The camera error as one plain sentence key (assistant.camera*). */
function failureKey(err: unknown): "cameraDenied" | "cameraNone" | "cameraBusy" | "cameraFailed" {
  const name = err instanceof DOMException ? err.name : "";
  if (name === "NotAllowedError" || name === "SecurityError") return "cameraDenied";
  if (name === "NotFoundError" || name === "OverconstrainedError") return "cameraNone";
  if (name === "NotReadableError" || name === "AbortError") return "cameraBusy";
  return "cameraFailed";
}

/**
 * Take a photo for the Assistant. Capture starts only from the person's click on "Take a photo"; a red
 * on-air dot shows while the camera is live; closing, retaking into review and unmounting all release
 * every track. The shot is a JPEG (long edge at most 1600 px) handed to onCapture as a File, so it goes
 * through the same attach path as a picked file. Where the browser offers no camera (plain http, no
 * device) the sheet says so and offers the system picker instead.
 */
export default function CameraCapture({ onCapture, onClose }: { onCapture: (file: File) => void; onClose: () => void }) {
  const t = useTranslations("assistant");
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const shotRef = useRef<Blob | null>(null);
  const [stage, setStage] = useState<Stage>("starting");
  const [error, setError] = useState<string | null>(null);
  const [facing, setFacing] = useState<"user" | "environment">("user");
  const [canFlip, setCanFlip] = useState(false);
  const [shotUrl, setShotUrl] = useState<string | null>(null);
  const fallbackRef = useRef<HTMLInputElement>(null);

  const release = useCallback(() => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }, []);

  const start = useCallback(
    async (mode: "user" | "environment") => {
      release();
      setError(null);
      setStage("starting");
      if (!navigator.mediaDevices?.getUserMedia) {
        setError(t("cameraUnsupported"));
        setStage("unavailable");
        return;
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: mode, width: { ideal: 1920 } }, audio: false });
        streamRef.current = stream;
        const video = videoRef.current;
        if (video) {
          video.srcObject = stream;
          await video.play().catch((): void => undefined);
        }
        const devices = await navigator.mediaDevices.enumerateDevices().catch((): MediaDeviceInfo[] => []);
        setCanFlip(devices.filter((d) => d.kind === "videoinput").length > 1);
        setStage("live");
      } catch (err) {
        setError(t(failureKey(err)));
        setStage("unavailable");
      }
    },
    [release, t]
  );

  useEffect(() => {
    void start(facing);
    return release;
    // start() on mount and when the camera is flipped only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [facing]);

  useEffect(
    () => () => {
      if (shotUrl) URL.revokeObjectURL(shotUrl);
    },
    [shotUrl]
  );

  function snap() {
    const video = videoRef.current;
    if (!video || !video.videoWidth) return;
    const scale = Math.min(1, MAX_EDGE / Math.max(video.videoWidth, video.videoHeight));
    const canvas = document.createElement("canvas");
    canvas.width = Math.round(video.videoWidth * scale);
    canvas.height = Math.round(video.videoHeight * scale);
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    // The selfie preview is mirrored; the saved photo is not.
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(
      (blob) => {
        if (!blob) return;
        shotRef.current = blob;
        setShotUrl(URL.createObjectURL(blob));
        release();
        setStage("review");
      },
      "image/jpeg",
      0.9
    );
  }

  function use() {
    const blob = shotRef.current;
    if (!blob) return;
    onCapture(new File([blob], `photo-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, "-")}.jpg`, { type: "image/jpeg" }));
    onClose();
  }

  return (
    <AssistantOverlay title={t("cameraTitle")} closeLabel={t("close")} onClose={onClose}>
      <div className="px-4 pb-4">
        <div className="relative aspect-[4/3] w-full overflow-hidden rounded-2xl bg-black">
          <video
            ref={videoRef}
            playsInline
            muted
            aria-label={t("cameraPreview")}
            className={`absolute inset-0 size-full object-cover ${facing === "user" ? "-scale-x-100" : ""} ${stage === "live" || stage === "starting" ? "" : "hidden"}`}
          />
          {stage === "review" && shotUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={shotUrl} alt={t("cameraShot")} className="absolute inset-0 size-full object-cover" />
          )}
          {stage === "starting" && (
            <p role="status" className="absolute inset-0 grid place-items-center text-sm text-white/80">
              {t("cameraStarting")}
            </p>
          )}
          {stage === "unavailable" && (
            <div className="absolute inset-0 grid place-items-center p-6 text-center">
              <p role="alert" className="text-sm text-white/90">
                {error}
              </p>
            </div>
          )}
          {stage === "live" && (
            <span className="absolute left-3 top-3 inline-flex items-center gap-1.5 rounded-full bg-black/55 px-2.5 py-1 text-[11px] font-medium text-white">
              <LiveDot state="--state-error" />
              {t("cameraOn")}
            </span>
          )}
        </div>

        <div className="mt-4 flex items-center justify-center gap-4">
          {stage === "live" && (
            <>
              <span className="w-10" />
              <button
                type="button"
                onClick={snap}
                aria-label={t("cameraSnap")}
                title={t("cameraSnap")}
                className="grid size-16 place-items-center rounded-full border-4 border-foreground/80 bg-background p-1 transition-transform active:scale-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
              >
                <span className="size-full rounded-full bg-foreground" />
              </button>
              {canFlip ? (
                <Button
                  type="button"
                  size="icon"
                  variant="outline"
                  className="size-10 rounded-full"
                  aria-label={t("cameraFlip")}
                  title={t("cameraFlip")}
                  onClick={() => setFacing((f) => (f === "user" ? "environment" : "user"))}
                >
                  <SwitchCamera size={16} aria-hidden="true" />
                </Button>
              ) : (
                <span className="w-10" />
              )}
            </>
          )}
          {stage === "review" && (
            <>
              <Button type="button" variant="outline" onClick={() => void start(facing).then(() => setShotUrl(null))}>
                <RotateCcw size={14} aria-hidden="true" />
                {t("cameraRetake")}
              </Button>
              <Button type="button" onClick={use}>
                <Check size={14} aria-hidden="true" />
                {t("cameraUse")}
              </Button>
            </>
          )}
          {stage === "unavailable" && (
            <>
              <Button type="button" variant="outline" onClick={() => void start(facing)}>
                <RefreshCw size={14} aria-hidden="true" />
                {t("cameraTryAgain")}
              </Button>
              <Button type="button" onClick={() => fallbackRef.current?.click()}>
                <Camera size={14} aria-hidden="true" />
                {t("cameraUseSystem")}
              </Button>
              <input
                ref={fallbackRef}
                type="file"
                accept="image/*"
                capture="environment"
                className="sr-only"
                tabIndex={-1}
                aria-label={t("cameraUseSystem")}
                onChange={(e) => {
                  const file = e.target.files?.[0];
                  e.target.value = "";
                  if (file) {
                    onCapture(file);
                    onClose();
                  }
                }}
              />
            </>
          )}
        </div>
        <p className="mt-3 text-center text-[11px] text-muted-foreground">{t("cameraPrivacy")}</p>
      </div>
    </AssistantOverlay>
  );
}
