"use client";

import { useTranslations } from "../../../lib/i18n";
import AgentLab from "../lab/AgentLab";
import { ExpandedReplyOverlay } from "./ExpandedReplyOverlay";
import type { PlaygroundAgent, PlaygroundTurn } from "./types";

/** The two overlays the playground hosts: the full-screen reply reader and the Agent Lab. */
export function LabOverlayHost({
  agent,
  sessionId,
  turns,
  busy,
  expandedTurnIndex,
  onCloseExpanded,
  labOpen,
  onCloseLab,
  onCancelTurn,
  onForkRerun,
}: {
  agent: PlaygroundAgent | null;
  sessionId: string | null;
  turns: PlaygroundTurn[];
  busy: boolean;
  expandedTurnIndex: number | null;
  onCloseExpanded: () => void;
  labOpen: boolean;
  onCloseLab: () => void;
  onCancelTurn: () => void;
  onForkRerun: (keepTurnCount: number, message: string) => void;
}) {
  const t = useTranslations("agentPlayground");
  return (
    <>
      {expandedTurnIndex != null && turns[expandedTurnIndex] && (
        <ExpandedReplyOverlay
          text={turns[expandedTurnIndex].content}
          trace={turns[expandedTurnIndex].trace}
          onClose={onCloseExpanded}
          closeLabel={t("closeExpandedReply")}
        />
      )}
      <AgentLab
        open={labOpen}
        onClose={onCloseLab}
        agent={agent}
        sessionId={sessionId}
        turns={turns}
        busy={busy}
        // Both cancel paths (server request controller, on-device abort
        // controller) are wired by the shell; each is a no-op when that
        // kind of turn is not the one running.
        onCancelTurn={onCancelTurn}
        onForkRerun={onForkRerun}
      />
    </>
  );
}
