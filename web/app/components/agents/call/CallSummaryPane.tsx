"use client";

// Presentational pane for the running transcript and any call / turn error.
// Stays readable after hang-up (the summary phase). No state of its own.

import type { ComponentProps } from "react";
import CallTranscriptPanel from "../CallPanel/CallTranscriptPanel";
import CallErrorBox from "../CallPanel/CallErrorBox";

interface CallSummaryPaneProps {
  transcript: ComponentProps<typeof CallTranscriptPanel>;
  error: ComponentProps<typeof CallErrorBox>;
}

export default function CallSummaryPane({ transcript, error }: CallSummaryPaneProps) {
  return (
    <>
      <CallTranscriptPanel {...transcript} />
      <CallErrorBox {...error} />
    </>
  );
}
