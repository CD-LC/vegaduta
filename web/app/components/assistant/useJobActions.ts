"use client";

import { useCallback, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import type { AgentJobSummaryResponse } from "../../lib/api/types";
import { errorMessage } from "../../lib/errors";
import { useTranslations } from "../../lib/i18n";
import { jobPhase } from "./backgroundWork";
import type { AssistantTurn } from "./assistantTypes";
import { nextTurnId } from "./useAssistantTurn";

/**
 * What the work rail can do with a background task (lifted out of AssistantPane): show its outcome as a reply in
 * the conversation on screen, or stop it. `stoppingId` is the task whose stop is in flight.
 */
export function useJobActions({
  appendTurns,
  onError,
  onChanged,
}: {
  appendTurns: (...entries: AssistantTurn[]) => void;
  onError: (message: string) => void;
  /** A stop was sent (or failed): the rail's counts should be read again. */
  onChanged: () => void;
}) {
  const t = useTranslations("assistant");
  const [stoppingId, setStoppingId] = useState<string | null>(null);

  /** A finished (or failed, or stopped) task's outcome as a reply in the conversation on screen. */
  const showJobResult = useCallback(
    async (job: AgentJobSummaryResponse) => {
      const note = job.goalPreview ?? null;
      try {
        const full = await assistantApi.getJob(job.agentId, job.id);
        const phase = jobPhase(full.status);
        const text =
          phase === "done"
            ? full.resultText?.trim() || t("taskNoResult")
            : phase === "stopped"
              ? t("taskWasStopped")
              : full.errorMessage || t("taskFailedPlain");
        appendTurns({ id: nextTurnId("a"), role: "assistant", content: text, at: Date.now(), note, failed: phase === "failed" });
      } catch (err) {
        appendTurns({ id: nextTurnId("a"), role: "assistant", content: errorMessage(err), at: Date.now(), failed: true });
      }
    },
    [appendTurns, t]
  );

  const stopJob = useCallback(
    async (job: AgentJobSummaryResponse) => {
      setStoppingId(job.id);
      try {
        await assistantApi.cancelJob(job.agentId, job.id);
      } catch (err) {
        onError(errorMessage(err, t("taskStopFailed")));
      } finally {
        setStoppingId(null);
        onChanged();
      }
    },
    [onError, onChanged, t]
  );

  return { showJobResult, stopJob, stoppingId };
}
