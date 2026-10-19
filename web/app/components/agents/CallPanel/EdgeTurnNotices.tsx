"use client";



interface EdgeTurnNoticesProps {
  edgeHandledSummary: string | null;
  audioUnavailableNotice: string | null;
  edgeFallbackNotice: string | null;
}

export default function EdgeTurnNotices({ edgeHandledSummary, audioUnavailableNotice, edgeFallbackNotice }: EdgeTurnNoticesProps) {
  return (
    <>
      {/* Live user report (2026-08-07): positive counterpart to
          edgeFallbackNotice below - shows which on-device component chain
          actually answered a successful on-device voice turn, so "edge is
          the first choice" is visibly true rather than silently assumed. */}
      {edgeHandledSummary && (
        <div className="rounded-lg border border-border bg-secondary/30 px-2.5 py-1.5 text-[11px] text-muted-foreground">
          <span className="font-medium text-foreground">On-device:</span> {edgeHandledSummary}
        </div>
      )}

      {/* Part 5(c) fix #2: non-fatal notice, same posture as callNotice above
          - the turn worked (reply text is right below), only audio didn't. */}
      {audioUnavailableNotice && (
        <div className="rounded-lg border border-border bg-secondary/40 px-2.5 py-1.5 text-xs text-muted-foreground">
          {audioUnavailableNotice}
        </div>
      )}

      {/* Mirrors AgentPlayground's "Platform (edge skipped)" pill copy, same
          posture as audioUnavailableNotice above - a separate state so a
          turn that both fell back to hosted reasoning AND had an audio hiccup
          could in theory show both, though in practice a hosted-reasoning
          turn no longer touches this component's own TTS path at all. */}
      {edgeFallbackNotice && (
        <div className="rounded-lg border border-border bg-secondary/40 px-2.5 py-1.5 text-xs text-muted-foreground">
          {edgeFallbackNotice}
        </div>
      )}
    </>
  );
}
