"use client";

// On-device voice models (Kokoro TTS, in-browser Whisper STT): the download
// state machines, the "server has no voice" evidence, and the preflight action
// dispatcher. Lifted out of CallPanel.tsx unchanged; the component still owns
// the turn pipeline, so everything it shares with a download (refs, error and
// stage setters, the replay of a parked utterance) is passed in.

import { useRef, useState, type Dispatch, type MutableRefObject, type SetStateAction } from "react";
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { edgeTtsAvailable, ensureTtsModel, isTtsModelCached } from "../../../lib/edge/audio/kokoro";
import { ensureSttModel } from "../../../lib/edge/audio/whisper";
import { decideVoiceModelFetch, isLatinScriptText } from "../../../lib/voice/voiceOutput";
import { progressPercent, readConnectionHints } from "../../../lib/voice/callEnvironment";
import {
  displaySafeDownloadError,
  parkedUtteranceIsReplayable,
  type DownloadState,
  type PreflightActionKind,
} from "../../../lib/voice/callPreflight";
import type { CallStage } from "../CallPanel/callStage";
import { writeServerVoiceMissingFlag } from "./voiceHelpers";

interface UseVoiceModelDownloadsArgs {
  unmountedRef: MutableRefObject<boolean>;
  callStartedRef: MutableRefObject<boolean>;
  pendingSttRef: MutableRefObject<{ blob: Blob; at: number } | null>;
  recordingRef: MutableRefObject<boolean>;
  pipelineBusyRef: MutableRefObject<boolean>;
  serverSttFailedAtRef: MutableRefObject<number | null>;
  setServerSttFailedAt: Dispatch<SetStateAction<number | null>>;
  setLocalTtsInstalled: (installed: boolean) => void;
  setLocalSttInstalled: (installed: boolean) => void;
  setAudioUnavailableNotice: (notice: string | null) => void;
  setPipelineError: (message: string | null) => void;
  setCallStage: Dispatch<SetStateAction<CallStage>>;
  setSttReadyNotice: (notice: string | null) => void;
  setShowMicTest: (show: boolean) => void;
  refreshSystemHealth: () => void;
  /** Re-runs a parked recording through in-browser Whisper; resolves false if it could not. */
  sendTurnViaLocalStt: (blob: Blob) => Promise<boolean>;
}

export function useVoiceModelDownloads({
  unmountedRef,
  callStartedRef,
  pendingSttRef,
  recordingRef,
  pipelineBusyRef,
  serverSttFailedAtRef,
  setServerSttFailedAt,
  setLocalTtsInstalled,
  setLocalSttInstalled,
  setAudioUnavailableNotice,
  setPipelineError,
  setCallStage,
  setSttReadyNotice,
  setShowMicTest,
  refreshSystemHealth,
  sendTurnViaLocalStt,
}: UseVoiceModelDownloadsArgs) {
  const [sttDownload, setSttDownload] = useState<DownloadState>({ status: "idle" });
  const [ttsDownload, setTtsDownload] = useState<DownloadState>({ status: "idle" });
  // Evidence, not configuration: there is no tenant-facing "is a voice
  // provider set up" endpoint, so "no server voice" is learned from a reply
  // the SERVER marked voiceless (method "text-only-fallback"), or from a turn
  // that took a path with no server voice at all. It follows the LATEST such
  // reply: true only when that reply was in a script the on-device voice can
  // speak, so the English voice is never offered under a Hindi reply.
  const [serverVoiceMissing, setServerVoiceMissing] = useState(false);
  const ttsFetchAttemptedRef = useRef(false);
  const ttsDownloadingRef = useRef(false);
  const sttDownloadingRef = useRef(false);

  // `replyText` is the voiceless reply (or fragment). Kokoro speaks English
  // only, so for a reply it cannot voice nothing is recorded, offered or
  // fetched - and an offer left over from an earlier English reply is taken
  // down again.
  function noteServerVoiceMissing(replyText: string | null | undefined) {
    if (unmountedRef.current) return;
    const speakable = isLatinScriptText(replyText);
    setServerVoiceMissing(speakable);
    if (!speakable) return;
    writeServerVoiceMissingFlag();
    void maybeFetchDefaultVoice();
  }

  // Kokoro is the default voice, so a caller with no server voice should end
  // up on it without hunting for a download button - but it is ~90 MB from a
  // third-party model hub. decideVoiceModelFetch: automatic ONLY when the
  // browser positively reports an unmetered-looking desktop connection; a
  // one-tap offer (rendered by the preflight) for everyone else, which
  // includes every Firefox and Safari. One attempt per page load, only after
  // the person has started a call AND an English-speakable reply came back
  // voiceless (the only caller is noteServerVoiceMissing, which checks that).
  async function maybeFetchDefaultVoice() {
    if (ttsFetchAttemptedRef.current) return;
    let supported = false;
    try {
      supported = await edgeTtsAvailable();
    } catch {
      supported = false;
    }
    if (unmountedRef.current) return;
    const hints = readConnectionHints();
    const decision = decideVoiceModelFetch({
      edgeOn: edgeEnabled(),
      kokoroSupported: supported,
      kokoroInstalled: isTtsModelCached(),
      serverVoiceMissing: true,
      replySpeakable: true,
      callStarted: callStartedRef.current,
      alreadyAttempted: ttsFetchAttemptedRef.current,
      saveData: hints.saveData,
      effectiveType: hints.effectiveType,
      cellular: hints.cellular,
      mobile: hints.mobile,
      connectionKnown: hints.connectionKnown,
    });
    if (decision === "auto") void startTtsDownload();
  }

  // ensureTtsModel is bounded by kokoro.ts's own idle watchdog (120s without
  // a progress event fails the load), so "downloading" can never spin forever.
  async function startTtsDownload() {
    if (ttsDownloadingRef.current) return;
    ttsDownloadingRef.current = true;
    ttsFetchAttemptedRef.current = true;
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
      setAudioUnavailableNotice(null);
    } catch (err) {
      console.warn("[Voice] on-device voice download failed:", err);
      if (!unmountedRef.current) setTtsDownload({ status: "failed", error: displaySafeDownloadError(err) });
    } finally {
      ttsDownloadingRef.current = false;
    }
  }

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
      // "...and then the call proceeds using it": if the utterance whose
      // server transcription failed is STILL the latest thing the person
      // said (nothing cleared it, it is under two minutes old, and no
      // recording or turn is running right now), finish it rather than make
      // them say it again. Otherwise it is dropped for good - a download can
      // take minutes and the conversation may have moved on.
      const parked = pendingSttRef.current;
      pendingSttRef.current = null;
      const replay =
        !!parked &&
        parkedUtteranceIsReplayable({
          parkedAt: parked.at,
          now: Date.now(),
          recording: recordingRef.current,
          pipelineBusy: pipelineBusyRef.current,
        });
      if (parked && replay) {
        setPipelineError(null);
        const ok = await sendTurnViaLocalStt(parked.blob);
        if (!ok && !unmountedRef.current) {
          setPipelineError(
            "On-device speech-to-text is ready, but it could not make out that last recording. Hold to talk and say it again."
          );
          setCallStage("failed");
        }
      } else if (!recordingRef.current && !pipelineBusyRef.current) {
        // Nothing to finish. Take down the "your recording is kept" error if
        // it is still showing - it is no longer true.
        setPipelineError(null);
        setCallStage((prev) => (prev === "failed" ? "idle" : prev));
        setSttReadyNotice("On-device speech-to-text is ready. Hold to talk.");
      }
    } catch (err) {
      console.warn("[Voice] on-device speech-to-text download failed:", err);
      if (!unmountedRef.current) setSttDownload({ status: "failed", error: displaySafeDownloadError(err) });
    } finally {
      sttDownloadingRef.current = false;
    }
  }

  function handlePreflightAction(kind: PreflightActionKind) {
    switch (kind) {
      case "download-stt":
        void startSttDownload();
        break;
      case "download-tts":
        void startTtsDownload();
        break;
      case "recheck-server":
        // A fresh probe is the only honest way to find out; forget the last
        // failure so the next hold tries the server again either way.
        serverSttFailedAtRef.current = null;
        setServerSttFailedAt(null);
        pendingSttRef.current = null;
        refreshSystemHealth();
        break;
      case "test-mic":
        setShowMicTest(true);
        break;
      default:
        break;
    }
  }

  return {
    sttDownload,
    ttsDownload,
    serverVoiceMissing,
    noteServerVoiceMissing,
    startSttDownload,
    startTtsDownload,
    handlePreflightAction,
  };
}
