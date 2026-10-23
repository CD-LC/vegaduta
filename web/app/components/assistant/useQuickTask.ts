"use client";

import { useCallback, useRef } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import type { ActivityStep } from "../agents/AgentActivityTrace";
import type { QuickTaskStreamEvent } from "../quickTask/model";
import type { AssistantTask, AssistantTurn, TaskChannel, TaskPreview } from "./assistantTypes";
import { nextTurnId, type useAssistantTurn } from "./useAssistantTurn";

type Turn = ReturnType<typeof useAssistantTurn>;

/**
 * The Quick Task mode of the box (the old /quick-task page, folded in 2026-10-18): one card in the
 * transcript walks preview → confirm → run → result over the same endpoints SimpleTaskRunner used
 * (POST /api/quick-task/preview, POST /api/quick-task/stream). The server picks the agent when the
 * person left the picker on "Auto"; a chosen agent is sent as `agentId`. Tool calls stream into the
 * card's work trace (WorkedForBlock) like a chat turn's step events.
 */
export function useQuickTask(turn: Turn, onSettled: (firstUserLine: string) => void) {
  const turnsRef = useRef<AssistantTurn[]>(turn.turns);
  turnsRef.current = turn.turns;

  const patchTask = useCallback(
    (cardId: string, patch: Partial<AssistantTask>, extra: Partial<AssistantTurn> = {}) => {
      turn.setTurns((list) =>
        list.map((x) => (x.id === cardId && x.task ? { ...x, ...extra, task: { ...x.task, ...patch } } : x))
      );
    },
    [turn]
  );

  /** Echo the request, then show the plan the server previews; the person confirms before anything runs. */
  const start = useCallback(
    async (message: string, agentId: string | null) => {
      const cardId = nextTurnId("a");
      const now = Date.now();
      // Phone number on file decides whether anything but LOG can deliver; read once per card, quietly.
      const account = await api.getAccount().catch((): null => null);
      const needsPhone = !(account as { phoneNumber?: string | null } | null)?.phoneNumber;
      turn.appendTurns(
        { id: nextTurnId("u"), role: "user", content: message, at: now },
        {
          id: cardId,
          role: "assistant",
          content: "",
          at: now,
          task: { message, agentId, channel: "LOG", status: "previewing", preview: null, needsPhone },
        }
      );
      try {
        const preview = (await api.previewQuickTask(message, agentId ?? "")) as TaskPreview;
        patchTask(cardId, { preview: preview ?? null, status: "confirm" });
      } catch (err) {
        patchTask(cardId, { status: "failed", error: errorMessage(err) }, { endedAt: Date.now() });
        onSettled(message);
      }
    },
    [turn, patchTask, onSettled]
  );

  const setChannel = useCallback((cardId: string, channel: TaskChannel) => patchTask(cardId, { channel }), [patchTask]);

  /** Save the delivery number, then unlock the channels on every card that was waiting for one. */
  const savePhone = useCallback(
    async (phone: string) => {
      await api.setAccountPhoneNumber(phone);
      turn.setTurns((list) =>
        list.map((x) => (x.task?.needsPhone ? { ...x, task: { ...x.task, needsPhone: false } } : x))
      );
    },
    [turn]
  );

  const cancel = useCallback(
    (cardId: string) => {
      turn.setTurns((list) => list.filter((x) => x.id !== cardId));
    },
    [turn]
  );

  /** Run it. Tool calls become tool_call/tool_result steps so the card shows "Working for …" like a turn. */
  const confirm = useCallback(
    async (cardId: string) => {
      const card = turnsRef.current.find((x) => x.id === cardId);
      if (!card?.task || card.task.status !== "confirm") return;
      const { message, channel, agentId } = card.task;
      let steps: ActivityStep[] = [];
      let stepId = 0;
      patchTask(cardId, { status: "running" }, { startedAt: Date.now(), endedAt: null, steps });
      try {
        await api.runQuickTaskStream<QuickTaskStreamEvent>(
          message,
          channel,
          (event) => {
            if (event.type === "started") {
              patchTask(cardId, { taskId: event.sessionId, preview: mergePreview(card.task?.preview, event) });
            } else if (event.type === "tool_call") {
              const id = ++stepId;
              steps = [
                ...steps,
                {
                  type: "tool_call",
                  id,
                  tool: event.toolName ?? "tool",
                  args: event.toolInput ?? undefined,
                  origin: "server",
                },
                event.errorMessage
                  ? {
                      type: "error",
                      id,
                      tool: event.toolName ?? "tool",
                      message: event.errorMessage,
                      ms: event.durationMs,
                      origin: "server",
                    }
                  : {
                      type: "tool_result",
                      id,
                      tool: event.toolName ?? "tool",
                      preview: event.toolOutput ?? undefined,
                      ms: event.durationMs,
                      origin: "server",
                    },
              ];
              patchTask(cardId, {}, { steps });
            } else if (event.type === "done") {
              patchTask(
                cardId,
                { status: "done", resultText: event.resultText, deliveryNote: event.deliveryNote ?? null },
                { content: event.resultText ?? "", endedAt: Date.now() }
              );
            } else if (event.type === "error") {
              patchTask(
                cardId,
                { status: "failed", error: event.message || "The task failed." },
                { endedAt: Date.now() }
              );
            }
          },
          agentId ?? ""
        );
        // A stream that closed without `done` or `error` is a cut connection, not a result.
        const after = turnsRef.current.find((x) => x.id === cardId)?.task;
        if (after?.status === "running") {
          patchTask(
            cardId,
            { status: "failed", error: "The task stopped before it reported a result." },
            { endedAt: Date.now() }
          );
        }
      } catch (err) {
        patchTask(cardId, { status: "failed", error: errorMessage(err) }, { endedAt: Date.now() });
      }
      onSettled(message);
    },
    [patchTask, onSettled]
  );

  return { start, confirm, cancel, setChannel, savePhone };
}

function mergePreview(
  preview: TaskPreview | null | undefined,
  started: Extract<QuickTaskStreamEvent, { type: "started" }>
): TaskPreview {
  return {
    ...(preview ?? {}),
    agentId: started.agentId ?? preview?.agentId ?? null,
    agentName: started.agentName ?? preview?.agentName ?? null,
    providerName: started.providerName ?? preview?.providerName ?? null,
    model: started.model ?? preview?.model ?? null,
  };
}
