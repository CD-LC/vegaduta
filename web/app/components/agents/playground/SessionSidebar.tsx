"use client";

import { useState, type ComponentProps } from "react";
import dynamic from "next/dynamic";
import { ChevronDown, ChevronUp } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import type { StagedFrame } from "../CameraPanel";
import ShareAgentPanel from "../ShareAgentPanel";
import type { PlaygroundAgent, AttachCapabilities } from "./types";

// FE-051: the voice-call (LiveKit, STT/TTS chain) and Sight camera/screen
// panels are client-only and heavy; split them out of the playground's
// initial chunk. Type-only imports of CameraPanel are erased, so the split holds.
const CallPanel = dynamic(() => import("../CallPanel"), { ssr: false });
const CameraPanel = dynamic(() => import("../CameraPanel"), { ssr: false });

type CameraPanelHandlers = Pick<
  ComponentProps<typeof CameraPanel>,
  "runWatchTurn" | "appendLocalTurn" | "matchRecognizedFace" | "withRecognizedName"
>;

/** The controls zone above the chat log: voice call, Sight camera/screen and the share link. */
export function SessionSidebar({
  agent,
  sessionId,
  onSessionId,
  capabilities,
  capsLoading,
  edgeOptIn,
  edgeVisionReady,
  busy,
  stagedFrames,
  onStagedFramesChange,
  visionOnDevice,
  onVisionOnDeviceChange,
  handlers,
}: {
  agent: PlaygroundAgent | null;
  sessionId: string | null;
  onSessionId: (id: string | null) => void;
  capabilities: AttachCapabilities;
  capsLoading: boolean;
  edgeOptIn: boolean;
  edgeVisionReady: boolean;
  busy: boolean;
  stagedFrames: StagedFrame[];
  onStagedFramesChange: (frames: StagedFrame[]) => void;
  visionOnDevice: boolean;
  onVisionOnDeviceChange: (on: boolean) => void;
  handlers: CameraPanelHandlers;
}) {
  const t = useTranslations("agentPlayground");
  // Collapsed shows the toggle bar only; the zone stays MOUNTED but hidden -
  // unmounting would hang up an in-flight call (CallPanel's cleanup
  // disconnects the LiveKit room) and drop a queued Sight capture.
  const [controlsOpen, setControlsOpen] = useState(false);
  return (
    <>
    <button
      type="button"
      className="flex w-full items-center gap-2 border-b border-border px-3 py-1.5 text-left text-xs font-medium text-muted-foreground hover:bg-accent/50 hover:text-foreground"
      aria-expanded={controlsOpen}
      data-testid="playground-controls-toggle"
      onClick={() => setControlsOpen((open) => !open)}
    >
      {controlsOpen ? (
        <ChevronUp className="size-3.5" aria-hidden="true" />
      ) : (
        <ChevronDown className="size-3.5" aria-hidden="true" />
      )}
      {t("controlsToggle")}
    </button>
    {/* Controls zone (voice call, Sight camera/screen, share link) is held
      to a 3:7 flex split against the chat log below - user ask
      (2026-09-28): the three panels took all the height their content
      wanted and squeezed the conversation to a sliver. flex-[3] and
      flex-[7] both use basis 0, so the split stays exactly 30/70 of the
      space between the header and the composer; the zone scrolls
      internally when an active call needs more than its share. Hidden while
      collapsed so the chat takes the full height. */}
    <div
      className={controlsOpen ? "flex min-h-0 flex-[3] flex-col overflow-y-auto" : "hidden"}
      data-testid="playground-controls"
    >
      <CallPanel agent={agent} sessionId={sessionId} onSessionId={onSessionId} />
      <CameraPanel
        agent={agent}
        // See edgeVisionReady's own comment above - a hosted/BYOK model that
        // can't see should still be OR'd with on-device vision being ready,
        // not overridden by it, so this keeps working exactly as before for
        // anyone not using on-device at all.
        canSee={capabilities.supportsImageInput || (edgeOptIn && edgeVisionReady)}
        capsLoading={capsLoading}
        busy={busy}
        stagedFrames={stagedFrames}
        onStagedFramesChange={onStagedFramesChange}
        visionOnDevice={visionOnDevice}
        onVisionOnDeviceChange={onVisionOnDeviceChange}
        runWatchTurn={handlers.runWatchTurn}
        appendLocalTurn={handlers.appendLocalTurn}
        matchRecognizedFace={handlers.matchRecognizedFace}
        withRecognizedName={handlers.withRecognizedName}
      />
      <ShareAgentPanel agent={agent} />
    </div>
    </>
  );
}
