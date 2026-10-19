"use client";

// Everything the call preflight (lib/voice/callPreflight.ts) needs to know
// about THIS device, gathered WITHOUT prompting for a permission or
// downloading anything: mic permission state, MediaRecorder support, and
// whether the on-device STT/TTS models are supported and already cached.

import { useEffect, useState } from "react";
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { edgeSttAvailable, isSttModelCached } from "../../../lib/edge/audio/whisper";
import { edgeTtsAvailable, isTtsModelCached } from "../../../lib/edge/audio/kokoro";
import { mediaRecorderSupported, probeMicState } from "../../../lib/voice/callEnvironment";
import type { MicState } from "../../../lib/voice/callPreflight";

export function useVoiceDeviceReadiness() {
  const [micState, setMicState] = useState<MicState>("unknown");
  const [recorderSupported, setRecorderSupported] = useState(true);
  const [localSttSupported, setLocalSttSupported] = useState(false);
  const [localTtsSupported, setLocalTtsSupported] = useState(false);
  const [localSttInstalled, setLocalSttInstalled] = useState(false);
  const [localTtsInstalled, setLocalTtsInstalled] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setRecorderSupported(mediaRecorderSupported());
    setLocalSttInstalled(edgeEnabled() && isSttModelCached());
    setLocalTtsInstalled(edgeEnabled() && isTtsModelCached());
    void probeMicState().then((state) => {
      if (!cancelled) setMicState(state);
    });
    // Both resolve false (never reject) when the edge surface is off in this
    // build - the preflight then offers no on-device route at all.
    void edgeSttAvailable().then((ok) => !cancelled && setLocalSttSupported(ok), () => {});
    void edgeTtsAvailable().then((ok) => !cancelled && setLocalTtsSupported(ok), () => {});
    return () => {
      cancelled = true;
    };
  }, []);

  return {
    micState,
    recorderSupported,
    localSttSupported,
    localTtsSupported,
    localSttInstalled,
    localTtsInstalled,
    setMicState,
    setRecorderSupported,
    setLocalSttInstalled,
    setLocalTtsInstalled,
  };
}
