"use client";

// /ui-kit/console-preview/agent-lab - the Agent Lab overlay opened over a
// sample agent and transcript (components/agents/lab/labFixtures.ts), with
// the lab's routes answered by the sample-data mock (see
// ../preview-harness.tsx and components/agents/lab/labMock.ts). The lab is
// normally reached from the playground's "Lab" button, which needs a real
// chat session behind it; this page shows all three instruments without one.
import { useState } from "react";
import ConsolePreview from "../preview-harness";
import AgentLab from "../../../components/agents/lab/AgentLab";
import AgentLabLauncher from "../../../components/agents/lab/AgentLabLauncher";
import { FIXTURE_AGENT, FIXTURE_TURNS } from "../../../components/agents/lab/labFixtures";
import type { LabTurn } from "../../../components/agents/lab/types";

export default function Preview() {
  const [open, setOpen] = useState(true);
  const [turns, setTurns] = useState<LabTurn[]>(FIXTURE_TURNS);
  return (
    <ConsolePreview label="agent-lab">
      <main style={{ padding: 32 }}>
        <AgentLabLauncher disabled={false} onOpen={() => setOpen(true)} />
      </main>
      <AgentLab
        open={open}
        onClose={() => setOpen(false)}
        agent={FIXTURE_AGENT}
        sessionId="preview-session"
        turns={turns}
        busy={false}
        onCancelTurn={() => {}}
        // Stands in for the playground's fork: cut the transcript, then
        // "answer" the re-sent message with a canned server exchange.
        onForkRerun={(keepTurnCount, message) =>
          setTurns((prev) => [
            ...prev.slice(0, keepTurnCount),
            { role: "user", content: message },
            {
              role: "assistant",
              content: "This is the branch's reply (sample data).",
              ref: `preview-fork-${keepTurnCount}-${prev.length}`,
              steps: [
                { type: "run_started", id: 1, origin: "server" },
                { type: "run_finished", id: 2, ms: 820, origin: "server" },
              ],
              metrics: { elapsedMs: 820, toolCalls: 0 },
            },
          ])
        }
      />
    </ConsolePreview>
  );
}
