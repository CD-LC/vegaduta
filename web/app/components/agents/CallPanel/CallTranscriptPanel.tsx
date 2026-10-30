"use client";

import type { RefObject } from "react";
import { FastForward, Loader2, Play, Square } from "lucide-react";
import type { ReplayState } from "../call/useReplyPlayback";
import { useTranslations } from "../../../lib/i18n";

/** The per-line "play this reply" state + handlers (call/useReplyPlayback) -
 * absent when the panel has no replay support wired. */
export interface TranscriptReplay {
  state: ReplayState | null;
  onReplay: (index: number, text: string) => void;
  onStop: () => void;
  onSkip: () => void;
}

interface CallTranscriptPanelProps {
  callTranscript: { role: "user" | "agent"; text: string }[];
  pipelineBusy: boolean;
  lastTranscript: string | null;
  lastReply: string | null;
  transcriptScrollRef: RefObject<HTMLDivElement | null>;
  replay?: TranscriptReplay;
}

export default function CallTranscriptPanel({ callTranscript, pipelineBusy, lastTranscript, lastReply, transcriptScrollRef, replay }: CallTranscriptPanelProps) {
    const tHC = useTranslations("hcComponentsAgentsCallPanelCallTranscriptPanel");
  return (
    <>
      {/* Direct report (2026-08-12): "it should display all transcribe of
          request and response" - the running record of every completed
          exchange this call/hold-to-talk session (callTranscript, captured
          by the pipelineBusy effect above), PLUS - while a turn is still in
          flight - the live in-progress transcript/reply below it, so
          incremental streaming (onReplyDelta) is still visible token by
          token exactly as before this change. The live pair disappears the
          instant pipelineBusy clears because by then the capture effect has
          already folded it into callTranscript on this same render pass -
          no separate "now committed" transition to coordinate. Scrollable
          and height-capped so a long call doesn't push the rest of the
          panel (mute/hang-up controls) off screen. */}
      {(callTranscript.length > 0 || (pipelineBusy && (lastTranscript || lastReply))) && (
        <div
          ref={transcriptScrollRef}
          className="max-h-64 space-y-1.5 overflow-y-auto rounded-lg border border-border bg-secondary/30 p-2.5 text-xs"
        >
          {callTranscript.map((entry, i) => {
            const playing = replay?.state?.index === i;
            return (
              <div key={i} className="group flex items-start gap-1.5">
                <div className="min-w-0 flex-1">
                  <span className="text-muted-foreground">{entry.role === "user" ? "You said: " : "Agent: "}</span>
                  {entry.text}
                </div>
                {/* Playground-parity audio control per agent line (play /
                    stop / skip-ahead for seekable hosted audio) - an explicit
                    play works even while the speaker toggle is muted, same
                    as the playground's per-turn control. */}
                {replay && entry.role === "agent" && (
                  <span className="flex shrink-0 items-center gap-0.5">
                    {playing && replay.state?.seekable && (
                      <button
                        type="button"
                        className="text-muted-foreground hover:text-foreground"
                        aria-label={tHC("s2")}
                        title={tHC("s2")}
                        onClick={replay.onSkip}
                      >
                        <FastForward size={12} />
                      </button>
                    )}
                    <button
                      type="button"
                      className="text-muted-foreground hover:text-foreground"
                      aria-label={playing ? "Stop" : "Play reply"}
                      title={playing ? "Stop" : "Play reply"}
                      onClick={() => (playing ? replay.onStop() : replay.onReplay(i, entry.text))}
                    >
                      {playing && replay.state?.phase === "loading" ? (
                        <Loader2 size={12} className="animate-spin" />
                      ) : playing ? (
                        <Square size={12} />
                      ) : (
                        <Play size={12} />
                      )}
                    </button>
                  </span>
                )}
              </div>
            );
          })}
          {pipelineBusy && lastTranscript && (
            <div>
              <span className="text-muted-foreground">{tHC("s1")} </span>
              {lastTranscript}
            </div>
          )}
          {pipelineBusy && lastReply && (
            <div>
              <span className="text-muted-foreground">Agent: </span>
              {lastReply}
            </div>
          )}
        </div>
      )}
    </>
  );
}
