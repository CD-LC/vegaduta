"use client";

// The share panel's on-device voice setup: capability probes (can this
// device run whisper/kokoro at all?), installed flags, the two download
// state machines, and the inputs evaluateCallPreflight needs. Lifted out
// of ShareCallPanel.tsx (file-size ratchet) - the panel keeps the turn
// pipeline; this hook owns nothing but model acquisition and readiness.

import { useEffect, useRef, useState, type MutableRefObject } from "react";
import { edgeEnabled } from "../../lib/edge/capabilities";
import { edgeTtsAvailable, ensureTtsModel, isTtsModelCached } from "../../lib/edge/audio/kokoro";
import { edgeSttAvailable, ensureSttModel, isSttModelCached } from "../../lib/edge/audio/whisper";
import { displaySafeDownloadError, type DownloadState } from "../../lib/voice/callPreflight";
import { progressPercent } from "../../lib/voice/callEnvironment";

export function useShareVoiceSetup(unmountedRef: MutableRefObject<boolean>) {
  const [localTtsSupported, setLocalTtsSupported] = useState(false);
  const [localTtsInstalled, setLocalTtsInstalled] = useState(false);
  const [ttsDownload, setTtsDownload] = useState<DownloadState>({ status: "idle" });
  const [localSttSupported, setLocalSttSupported] = useState(false);
  const [localSttInstalled, setLocalSttInstalled] = useState(false);
  const [sttDownload, setSttDownload] = useState<DownloadState>({ status: "idle" });
  const ttsDownloadingRef = useRef(false);
  const sttDownloadingRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    try {
      setLocalTtsInstalled(edgeEnabled() && isTtsModelCached());
      setLocalSttInstalled(edgeEnabled() && isSttModelCached());
    } catch {
      // Stays false.
    }
    void edgeTtsAvailable().then(
      (ok) => !cancelled && setLocalTtsSupported(ok),
      () => {}
    );
    void edgeSttAvailable().then(
      (ok) => !cancelled && setLocalSttSupported(ok),
      () => {}
    );
    return () => {
      cancelled = true;
    };
  }, []);

  // ensure*Model is bounded by the edge modules' idle watchdogs, so
  // "downloading" cannot spin forever; a rejection lands as a plain
  // line on the offer row - never a toast.
  async function startTtsDownload() {
    if (ttsDownloadingRef.current) return;
    ttsDownloadingRef.current = true;
    setTtsDownload({ status: "downloading", percent: null });
    try {
      await ensureTtsModel((p) => {
        if (unmountedRef.current) return;
        const pct = progressPercent(p);
        if (pct === null) return;
        setTtsDownload((prev) => (prev.status === "downloading" ? { status: "downloading", percent: pct } : prev));
      });
      if (unmountedRef.current) return;
      setLocalTtsInstalled(true);
      setTtsDownload({ status: "idle" });
    } catch (err) {
      console.warn("[Voice] on-device voice download failed:", err);
      if (!unmountedRef.current) setTtsDownload({ status: "failed", error: displaySafeDownloadError(err) });
    } finally {
      ttsDownloadingRef.current = false;
    }
  }

  // The on-device speech-to-text download - mirrors startTtsDownload.
  async function startSttDownload() {
    if (sttDownloadingRef.current) return;
    sttDownloadingRef.current = true;
    setSttDownload({ status: "downloading", percent: null });
    try {
      await ensureSttModel((p) => {
        if (unmountedRef.current) return;
        const pct = progressPercent(p);
        if (pct === null) return;
        setSttDownload((prev) => (prev.status === "downloading" ? { status: "downloading", percent: pct } : prev));
      });
      if (unmountedRef.current) return;
      setLocalSttInstalled(true);
      setSttDownload({ status: "idle" });
    } catch (err) {
      console.warn("[Voice] on-device speech-to-text download failed:", err);
      if (!unmountedRef.current) setSttDownload({ status: "failed", error: displaySafeDownloadError(err) });
    } finally {
      sttDownloadingRef.current = false;
    }
  }

  return {
    localTtsSupported,
    localTtsInstalled,
    ttsDownload,
    localSttSupported,
    localSttInstalled,
    sttDownload,
    startTtsDownload,
    startSttDownload,
  };
}
