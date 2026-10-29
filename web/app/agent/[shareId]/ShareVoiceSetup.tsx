"use client";

// The share page's on-device voice setup: one honest offer listing every
// small model this device could carry so talking works without the
// platform's speech services at all. Appears only while something
// installable is missing - once both rows are on the device (or the device
// can't run them), nothing is shown. Each download starts only from its
// own explicit button (the consent gesture the edge modules require) and
// shows live progress while it runs.

import { Loader2 } from "lucide-react";
import { Button } from "../../components/ui/button";
import { STT_MODEL_SIZE_BYTES } from "../../lib/edge/audio/whisper";
import { TTS_MODEL_SIZE_BYTES } from "../../lib/edge/audio/kokoro";
import type { DownloadState } from "../../lib/voice/callPreflight";
import { useTranslations } from "../../lib/i18n";

function approxMb(bytes: number): string {
  return `~${Math.round(bytes / 1_000_000)} MB`;
}

function Row({
  label,
  state,
  disabled,
  onDownload,
}: {
  label: string;
  state: DownloadState;
  disabled: boolean;
  onDownload: () => void;
}) {
  if (state.status === "downloading") {
    return (
      <div className="flex items-center gap-2" role="status">
        <Loader2 className="size-4 shrink-0 animate-spin text-primary" aria-hidden="true" />
        <span className="text-xs text-foreground">
          {label}
          {typeof state.percent === "number" ? ` - ${Math.round(state.percent)}%` : " - downloading…"}
        </span>
      </div>
    );
  }
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="text-xs text-muted-foreground">
        {label}
        {state.status === "failed" && state.error ? ` (failed: ${state.error})` : ""}
      </span>
      <Button type="button" size="sm" variant="outline" disabled={disabled} onClick={onDownload}>
        {state.status === "failed" ? "Retry" : "Download"}
      </Button>
    </div>
  );
}

export default function ShareVoiceSetup({
  sttInstallable,
  ttsInstallable,
  sttDownload,
  ttsDownload,
  busy,
  onSttDownload,
  onTtsDownload,
}: {
  sttInstallable: boolean;
  ttsInstallable: boolean;
  sttDownload: DownloadState;
  ttsDownload: DownloadState;
  busy: boolean;
  onSttDownload: () => void;
  onTtsDownload: () => void;
}) {
    const tHC = useTranslations("hcAgentShareIdShareVoiceSetup");
  if (!sttInstallable && !ttsInstallable) return null;
  return (
    <div className="flex flex-col gap-1.5 rounded-lg border border-border/60 bg-card/40 px-3 py-2">
      <p className="text-xs text-muted-foreground">
        {tHC("hcAgentShareIdShareVoiceSetup.s1")}
      </p>
      {sttInstallable && (
        <Row
          label={`On-device speech-to-text (${approxMb(STT_MODEL_SIZE_BYTES)}, English)`}
          state={sttDownload}
          disabled={busy}
          onDownload={onSttDownload}
        />
      )}
      {ttsInstallable && (
        <Row
          label={`On-device reply voice (${approxMb(TTS_MODEL_SIZE_BYTES)}, English)`}
          state={ttsDownload}
          disabled={busy}
          onDownload={onTtsDownload}
        />
      )}
    </div>
  );
}
