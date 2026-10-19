"use client";

import AgentPlayground from "../AgentPlayground";
import { GUIDE_ANCHORS } from "../guide/agentChecklist";
import { useWorkbench } from "./context";

export default function LabSection() {
  const { selectedAgent, initialLabOpen } = useWorkbench();
  return (
    <>
      {/* ---- PLAYGROUND ----
            Bounded to the viewport and pinned (sticky) on lg+ so it no longer
            stretches to match the tall config column — direct feedback:
            "Playground in agent is long in length, reduce it; on more chats it
            should have a scroll bar". The Card's own message body already
            scrolls (overflow-y-auto); capping the height here is what actually
            lets that scroll engage instead of the pane growing without limit. */}
      <div id={GUIDE_ANCHORS.playground} className="lg:sticky lg:top-20 lg:self-start lg:h-[calc(100vh-6rem)]">
        <AgentPlayground agent={selectedAgent} initialLabOpen={initialLabOpen} />
      </div>
    </>
  );
}
