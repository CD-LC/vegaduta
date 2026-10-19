"use client";

import type { RefObject } from "react";
import { AlertCircle } from "lucide-react";
import type { useTranslations } from "../../../lib/i18n";
import { callStageLabel, type CallStage } from "./callStage";

type Translate = ReturnType<typeof useTranslations>;

interface CallStatusNoticesProps {
  t: Translate;
  status: string;
  agentMissing: boolean;
  callNotice: string | null;
  callStage: CallStage;
  cameraOn: boolean;
  edgeSightActive: boolean;
  localVideoRef: RefObject<HTMLVideoElement | null>;
}

export default function CallStatusNotices({ t, status, agentMissing, callNotice, callStage, cameraOn, edgeSightActive, localVideoRef }: CallStatusNoticesProps) {
  return (
    <>
      {/* In-call notices: non-fatal, so styled as information rather than the
          destructive error box below - the call is up, but something the
          person would otherwise only discover by confusion (agent worker
          missing, camera fell back to voice-only, a device error) is named. */}
      {status === "connected" && (agentMissing || callNotice) && (
        <div className="flex flex-col gap-1 rounded-lg border border-border bg-secondary/40 p-2.5 text-xs text-muted-foreground">
          {agentMissing && (
            <span className="flex items-center gap-1.5">
              <AlertCircle size={14} className="shrink-0" style={{ color: "var(--state-warning)" }} />
              {t("agentNoJoin")}
            </span>
          )}
          {callNotice && <span>{callNotice}</span>}
        </div>
      )}

      {/* Part 5(b) step 6: the LiveKit call's own stage line, driven by the
          same shared callStage the DataReceived listener above sets -
          renders once voice-agent starts publishing "agent-stage" data
          messages (a parallel session's scope); harmlessly stays hidden
          (callStageLabel returns null for "idle") until then. */}
      {status === "connected" && callStageLabel(callStage) && (
        <div className="flex items-center gap-1.5 pl-1 text-xs text-muted-foreground">
          <span className="state-dot state-dot-live" style={{ ["--state" as any]: "var(--state-success)" } as any} aria-hidden="true" />
          {callStageLabel(callStage)}
        </div>
      )}

      {/* Local self-view for a sight call - muted (it's your own camera) with
          the same pulsing on-air treatment CameraPanel uses, so "the agent
          can see you" is never ambiguous. */}
      {cameraOn && (
        <div className="flex items-center gap-2.5">
          { }
          <video ref={localVideoRef} autoPlay muted playsInline className="h-24 rounded-lg border border-border object-cover" />
          <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <span className="state-dot state-dot-live" style={{ ["--state" as any]: "var(--state-error)" } as any} aria-hidden="true" />
            {/* Fix #7 (P1): the server-sight path (real frames streamed to
                and analyzed by the server) previously shared the generic
                "Camera on" label with edge-sight's "frames never leave your
                browser" line right above it - an asymmetric disclosure that
                implied every camera call was private. */}
            {edgeSightActive ? t("edgeSightOn") : t("cameraOnServerSight")}
          </span>
        </div>
      )}
    </>
  );
}
