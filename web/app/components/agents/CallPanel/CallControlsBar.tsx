"use client";

import {
  Loader2,
  Mic,
  MicOff,
  MonitorUp,
  MonitorX,
  Phone,
  PhoneOff,
  Video,
  VideoOff,
  Volume2,
  VolumeX,
} from "lucide-react";
import { Button } from "../../ui/button";
import { cn } from "../../../lib/utils";
import type { useTranslations } from "../../../lib/i18n";
import { useTranslations as useTnsHC } from "../../../lib/i18n";

type Translate = ReturnType<typeof useTranslations>;

interface CallControlsBarProps {
  t: Translate;
  status: string;
  reconnecting: boolean;
  muted: boolean;
  cameraOn: boolean;
  agentAudioMuted: boolean;
  canSee: boolean;
  /** In-call screen share (edge sight only - frames never leave the device). */
  screenSharing: boolean;
  toggleMute: () => void;
  toggleCamera: () => void;
  toggleScreenShare: () => void;
  toggleAgentAudio: () => void;
  endCall: () => void;
  startCall: (withCamera?: boolean) => void;
}

export default function CallControlsBar({
  t,
  status,
  reconnecting,
  muted,
  cameraOn,
  agentAudioMuted,
  canSee,
  screenSharing,
  toggleMute,
  toggleCamera,
  toggleScreenShare,
  toggleAgentAudio,
  endCall,
  startCall,
}: CallControlsBarProps) {
  const tHC = useTnsHC("hcComponentsAgentsCallPanelCallControlsBar");
  const statusLabel =
    status === "connected"
      ? reconnecting
        ? t("reconnecting")
        : "In call"
      : status === "connecting"
        ? "Connecting…"
        : status === "error"
          ? "Call failed"
          : "Not in a call";

  return (
    <>
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          {/* Shared .state-dot system — a live call now animates, so
              "connected" and "connecting" are told apart by motion as well as
              hue (they were two static coloured circles before). */}
          <span
            className={cn("state-dot", (status === "connected" || status === "connecting") && "state-dot-live")}
            style={
              {
                ["--state" as any]:
                  status === "connected"
                    ? reconnecting
                      ? "var(--state-warning)"
                      : "var(--state-success)"
                    : status === "connecting"
                      ? "var(--state-warning)"
                      : status === "error"
                        ? "var(--state-error)"
                        : "var(--state-idle)",
              } as any
            }
            aria-hidden="true"
          />
          {statusLabel}
        </div>
        {status === "connected" ? (
          <div className="flex items-center gap-1.5">
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={toggleMute}
              aria-label={muted ? "Unmute" : "Mute"}
            >
              {muted ? <MicOff size={14} /> : <Mic size={14} />}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={toggleCamera}
              aria-label={cameraOn ? t("cameraOffLabel") : t("cameraOnLabel")}
              title={cameraOn ? t("cameraOffLabel") : t("cameraOnLabel")}
            >
              {cameraOn ? <VideoOff size={14} /> : <Video size={14} />}
            </Button>
            {/* Always rendered: without edge sight the click surfaces the
                "needs the on-device vision model" notice instead of silently
                hiding the control (users asked where the button was). */}
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={toggleScreenShare}
              aria-label={screenSharing ? t("screenShareOffLabel") : t("screenShareOnLabel")}
              title={screenSharing ? t("screenShareOffLabel") : t("screenShareOnLabel")}
            >
              {screenSharing ? <MonitorX size={14} /> : <MonitorUp size={14} />}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={toggleAgentAudio}
              aria-label={agentAudioMuted ? "Resume agent audio" : "Stop agent audio"}
              title={agentAudioMuted ? "Resume agent audio" : "Stop agent audio"}
            >
              {agentAudioMuted ? <VolumeX size={14} /> : <Volume2 size={14} />}
            </Button>
            <Button type="button" variant="destructive" size="icon" onClick={endCall} aria-label={tHC("s1")}>
              <PhoneOff size={14} />
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-1.5">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => startCall()}
              disabled={status === "connecting"}
            >
              {status === "connecting" ? <Loader2 size={12} className="animate-spin" /> : <Phone size={12} />}
              Call
            </Button>
            {/* Fix #9 (P1): gated on the same canSee capability CameraPanel's
                own gate uses - a text-only agent (or a model with no vision
                support) must never be able to open a live server-side vision
                session. Rendered disabled (not hidden) so the control is
                discoverable; the title explains why it stays off. */}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => startCall(true)}
              disabled={status === "connecting" || !canSee}
              title={canSee ? t("callWithCameraTitle") : t("callWithCameraNeedsVision")}
            >
              <Video size={12} />
              {t("callWithCamera")}
            </Button>
          </div>
        )}
      </div>
    </>
  );
}
