"use client";

// Presentational pane for the dial / in-call controls: the Call button row,
// connection + camera notices and the hold-to-talk row. No state of its own.

import type { ComponentProps } from "react";
import CallControlsBar from "../CallPanel/CallControlsBar";
import CallStatusNotices from "../CallPanel/CallStatusNotices";
import PushToTalkRow from "../CallPanel/PushToTalkRow";

interface CallLivePaneProps {
  controls: ComponentProps<typeof CallControlsBar>;
  notices: ComponentProps<typeof CallStatusNotices>;
  pushToTalk: ComponentProps<typeof PushToTalkRow>;
}

export default function CallLivePane({ controls, notices, pushToTalk }: CallLivePaneProps) {
  return (
    <>
      <CallControlsBar {...controls} />
      <CallStatusNotices {...notices} />
      <PushToTalkRow {...pushToTalk} />
    </>
  );
}
