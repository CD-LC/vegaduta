"use client";

import { Loader2, Mic, Volume2 } from "lucide-react";
import { Button } from "../../ui/button";
import { callStageLabel, type CallStage } from "./callStage";
import { useTranslations } from "../../../lib/i18n";

interface PushToTalkRowProps {
  recording: boolean;
  pipelineBusy: boolean;
  pendingFallbackAsk: string | null;
  showMicTest: boolean;
  testingMicrophone: boolean;
  callStage: CallStage;
  startPushToTalk: () => void;
  stopPushToTalk: () => void;
  setShowMicTest: (show: boolean) => void;
}

export default function PushToTalkRow({
  recording,
  pipelineBusy,
  pendingFallbackAsk,
  showMicTest,
  testingMicrophone,
  callStage,
  startPushToTalk,
  stopPushToTalk,
  setShowMicTest,
}: PushToTalkRowProps) {
    const tHC = useTranslations("hcComponentsAgentsCallPanelPushToTalkRow");
  return (
    <>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          size="sm"
          variant={recording ? "destructive" : "secondary"}
          onMouseDown={startPushToTalk}
          onMouseUp={stopPushToTalk}
          onMouseLeave={() => recording && stopPushToTalk()}
          onTouchStart={startPushToTalk}
          onTouchEnd={stopPushToTalk}
          onKeyDown={(e) => {
            if ((e.key === " " || e.key === "Enter") && !e.repeat) {
              e.preventDefault();
              startPushToTalk();
            }
          }}
          onKeyUp={(e) => {
            if (e.key === " " || e.key === "Enter") {
              e.preventDefault();
              stopPushToTalk();
            }
          }}
          onBlur={() => recording && stopPushToTalk()}
          // Fix #11 (P2): pendingFallbackAsk's own 20s timeout resolves
          // whatever resolver is CURRENTLY in fallbackAskResolverRef, not
          // necessarily the one that scheduled it - a fast double-tap could
          // start a second turn (and a second askToFallback call) while an
          // earlier prompt is still pending, since there's a gap before
          // recording/pipelineBusy flip true. Disabling here too closes that
          // gap without needing a full resolver queue.
          disabled={pipelineBusy || !!pendingFallbackAsk}
        >
          {pipelineBusy ? (
            <Loader2 size={12} className="animate-spin" aria-hidden="true" />
          ) : (
            <Mic size={12} aria-hidden="true" />
          )}
          {recording ? "Release to send" : "Hold to talk"}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          onClick={() => setShowMicTest(!showMicTest)}
          disabled={testingMicrophone || recording || pipelineBusy}
          title={tHC("hcComponentsAgentsCallPanelPushToTalkRow.s2")}
        >
          <Volume2 size={12} aria-hidden="true" />
          {tHC("hcComponentsAgentsCallPanelPushToTalkRow.s1")}
        </Button>
        {/* Part 5(a): replaces the old single binary "Thinking…" label with
            the 7-stage indicator. Shown across BOTH "recording" (Listening,
            which precedes pipelineBusy) and "pipelineBusy" (everything from
            Transcribing through Speaking) so the label doesn't blink off
            between holding the button and the stream actually starting. */}
        {(recording || pipelineBusy) && (
          <span className="text-xs text-muted-foreground">{callStageLabel(callStage) || "Thinking…"}</span>
        )}
      </div>
    </>
  );
}
