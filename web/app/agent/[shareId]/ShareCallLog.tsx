"use client";

// The share page's retained transcript: every completed exchange for the
// visit (push-to-talk turns and live-call turns, the latter arriving over the
// room's "call-transcript" data topic), plus the in-flight turn's stage and
// partial text below. Agent lines get a replay button that speaks the line
// through the on-device/browser voices (there is no hosted /speak leg for
// an anonymous caller, and replay never fetches a model).

import { Play, Square } from "lucide-react";

interface ShareCallLogProps {
  callLog: { role: "user" | "agent"; text: string }[];
  stageText: string | null;
  transcript: string | null;
  reply: string;
  replayIndex: number | null;
  onReplay: (index: number, text: string) => void;
  onStopReplay: () => void;
}

export default function ShareCallLog({
  callLog,
  stageText,
  transcript,
  reply,
  replayIndex,
  onReplay,
  onStopReplay,
}: ShareCallLogProps) {
  if (callLog.length === 0 && !stageText && !transcript && !reply) return null;
  return (
    <div className="max-h-64 space-y-1 overflow-y-auto rounded-lg bg-secondary/30 px-3 py-2 text-xs">
      {stageText && <p className="font-medium text-primary">{stageText}</p>}
      {callLog.map((entry, i) => (
        <div key={i} className="flex items-start gap-1.5">
          <p
            className={
              entry.role === "user" ? "min-w-0 flex-1 text-muted-foreground" : "min-w-0 flex-1 text-foreground"
            }
          >
            {entry.role === "user" ? `You said: "${entry.text}"` : entry.text}
          </p>
          {entry.role === "agent" && (
            <button
              type="button"
              className="shrink-0 text-muted-foreground hover:text-foreground"
              aria-label={replayIndex === i ? "Stop" : "Play reply"}
              title={replayIndex === i ? "Stop" : "Play reply"}
              onClick={() => (replayIndex === i ? onStopReplay() : onReplay(i, entry.text))}
            >
              {replayIndex === i ? <Square size={12} /> : <Play size={12} />}
            </button>
          )}
        </div>
      ))}
      {transcript && <p className="text-muted-foreground">You said: &quot;{transcript}&quot;</p>}
      {reply && <p className="text-foreground">{reply}</p>}
    </div>
  );
}
