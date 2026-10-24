"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../lib/api";
import { assistantApi } from "../../lib/api/assistant";
import { triggerBrowserDownload } from "../../lib/api/core";
import { errorMessage } from "../../lib/errors";
import { useTranslations } from "../../lib/i18n";
import { defaultTimezone } from "../../lib/scheduledTasks";
import { researchAvailability } from "../research/researchRun";
import type { PendingAttachment } from "../ChatAttachments";
import type { AgentResponse } from "../../lib/api/types";
import type { StatusInput } from "../../connect/connectCatalog";
import type { AssistantTurn } from "./assistantTypes";
import { syncAssistantTools } from "./assistantAgent";
import { jobThreadId, type BackgroundTask } from "./backgroundWork";
import { assistantContext, attachedFilesContextLines, detectNeeds, unmetNeeds, withAppContext } from "./needs";
import {
  DEFAULT_CAPABILITIES,
  SLASH_COMMANDS,
  buildJobGoal,
  dispatch,
  type DispatchCapabilities,
  type ScheduleSpec,
} from "./dispatch";
import { nextTurnId, type TurnOutcome, type useAssistantTurn } from "./useAssistantTurn";

/** Which rungs above the plain turn are switched on for this deployment (read once per mount, quietly). */
export function useDispatchCapabilities(enabled: boolean): DispatchCapabilities {
  const [caps, setCaps] = useState<DispatchCapabilities>(DEFAULT_CAPABILITIES);
  useEffect(() => {
    if (!enabled) return undefined;
    let live = true;
    void Promise.all([
      assistantApi.getResearchStatus().catch((): null => null),
      assistantApi.getScheduledTasksConfig().catch((): null => null),
    ]).then(([research, scheduled]) => {
      if (!live) return;
      setCaps({
        research: researchAvailability(research) === "ready",
        scheduledTasks: !!scheduled?.enabled,
        jobs: true,
      });
    });
    return () => {
      live = false;
    };
  }, [enabled]);
  return caps;
}

type Turn = ReturnType<typeof useAssistantTurn>;

/**
 * Runs the dispatch ladder for what the person typed and performs the chosen rung with the existing
 * endpoints; every outcome lands in the transcript as a reply, a hand-off card or a failed bubble.
 */
export function useAssistantDispatch({
  agent,
  turn,
  caps,
  connections,
  attachments,
  onSettled,
  onNewThread,
  folder,
  onBackgroundStarted,
}: {
  /** The agent the box talks to; `isAssistant` false = another agent of the workspace, which gets the message as typed (no app-context block, no connect-first gate). */
  agent: { id: string; name: string; isAssistant?: boolean } | null;
  turn: Turn;
  caps: DispatchCapabilities;
  /** What is connected (useConnections): read fresh before a request that needs a service. */
  connections: { status: StatusInput; latest: { current: StatusInput }; refresh: () => Promise<StatusInput> };
  attachments: { items: PendingAttachment[]; clear: () => void; restore: (items: PendingAttachment[]) => void };
  /** A turn or hand-off settled: update the thread index and the work counts. */
  onSettled: (sessionId: string | null, firstUserLine: string) => void;
  onNewThread: () => void;
  /** A background task was handed over (the pane remembers which thread it came from). */
  onBackgroundStarted?: (task: BackgroundTask) => void;
  /** The folder attached in the picker (read at send time), or null. */
  folder?: { current: string | null };
}) {
  const t = useTranslations("assistant");
  const turnsRef = useRef<AssistantTurn[]>(turn.turns);
  turnsRef.current = turn.turns;

  const firstUserLine = useCallback(
    (fallback: string) => turnsRef.current.find((x) => x.role === "user")?.content ?? fallback,
    []
  );

  const reply = useCallback(
    (content: string, extra: Partial<AssistantTurn> = {}): AssistantTurn => ({
      id: nextTurnId("a"),
      role: "assistant",
      content,
      at: Date.now(),
      endedAt: Date.now(),
      ...extra,
    }),
    []
  );

  const finishTurn = useCallback(
    (outcome: TurnOutcome, message: string) => {
      onSettled(outcome.sessionId, firstUserLine(message));
      return outcome;
    },
    [onSettled, firstUserLine]
  );

  const plainTurn = useCallback(
    async (
      message: string,
      opts: {
        note?: string | null;
        targetAgentId?: string | null;
        replyNote?: string | null;
      } = {}
    ) => {
      const sent = attachments.items;
      attachments.clear();
      // The Assistant's own turns carry what is and is not connected, so it never has to guess.
      const sendText = opts.targetAgentId || agent?.isAssistant === false
        ? null
        : withAppContext(
            message,
            assistantContext(connections.latest.current, folder?.current ?? null, [
              ...attachedFilesContextLines(sent),
            ])
          );
      const outcome = await turn.runTurn(message, sent, { ...opts, sendText });
      // A failed send keeps the files so the person can fix and resend without re-picking them.
      if (!outcome.ok && !outcome.cancelled && sent.length > 0) attachments.restore(sent);
      return finishTurn(outcome, message);
    },
    [attachments, turn, finishTurn, connections.latest, folder, agent?.isAssistant]
  );

  const startJob = useCallback(
    async (
      request: string,
      thread: ReadonlyArray<{ role: "user" | "assistant"; content: string }>,
      echoRequest: boolean
    ) => {
      if (!agent) return;
      const now = Date.now();
      if (echoRequest) turn.appendTurns({ id: nextTurnId("u"), role: "user", content: request, at: now });
      try {
        const job = await assistantApi.startBackgroundJob(agent.id, buildJobGoal(thread, request));
        turn.appendTurns(
          reply("", {
            handoff: {
              kind: "job",
              id: job?.id ?? null,
              title: request,
              href: job?.id ? `/jobs/${agent.id}/${job.id}` : `/jobs/${agent.id}`,
            },
          })
        );
        // A thread whose first message went straight to the background has no session yet: the job names it,
        // so it is listed on the left and its card comes back when it is opened.
        const threadId = turn.sessionId ?? (job?.id ? jobThreadId(job.id) : null);
        if (job?.id && threadId) {
          onBackgroundStarted?.({ jobId: job.id, agentId: agent.id, title: request, threadId, startedAt: now });
        }
        onSettled(threadId, firstUserLine(request));
        return;
      } catch (err) {
        turn.appendTurns(reply(errorMessage(err), { failed: true }));
      }
      onSettled(turn.sessionId, firstUserLine(request));
    },
    [agent, turn, reply, onSettled, onBackgroundStarted, firstUserLine]
  );

  const startResearch = useCallback(
    async (question: string) => {
      turn.appendTurns({ id: nextTurnId("u"), role: "user", content: `/research ${question}`, at: Date.now() });
      try {
        const run = await assistantApi.startResearchRun({ question, depth: "auto" });
        turn.appendTurns(
          reply("", { handoff: { kind: "research", id: run?.id ?? null, title: question, href: "/research" } })
        );
      } catch (err) {
        turn.appendTurns(reply(errorMessage(err), { failed: true }));
      }
      onSettled(turn.sessionId, firstUserLine(question));
    },
    [turn, reply, onSettled, firstUserLine]
  );

  const startSchedule = useCallback(
    async (spec: ScheduleSpec, raw: string) => {
      if (!agent) return;
      turn.appendTurns({ id: nextTurnId("u"), role: "user", content: raw, at: Date.now() });
      const timezone = defaultTimezone();
      try {
        const preview = await assistantApi
          .previewScheduledTaskSchedule({ frequency: spec.frequency, time: spec.time, day: spec.day, timezone })
          .catch((): null => null);
        const task = await assistantApi.createScheduledTask({
          agentId: agent.id,
          name: spec.name,
          prompt: spec.prompt,
          frequency: spec.frequency,
          time: spec.time,
          day: spec.day,
          timezone,
          delivery: "in_app",
        });
        const next = preview?.nextRuns?.[0] ? formatWhen(preview.nextRuns[0]) : null;
        const detail = [preview?.description, next ? t("nextRun", { when: next }) : null].filter(Boolean).join(" · ");
        turn.appendTurns(
          reply("", {
            handoff: { kind: "schedule", id: task?.id ?? null, title: spec.prompt, detail, href: "/scheduled" },
          })
        );
      } catch (err) {
        turn.appendTurns(reply(errorMessage(err), { failed: true }));
      }
      onSettled(turn.sessionId, firstUserLine(raw));
    },
    [agent, turn, reply, onSettled, firstUserLine, t]
  );

  const exportThread = useCallback(async () => {
    if (!agent || !turn.sessionId) {
      turn.appendTurns(reply(t("exportNothing")));
      return;
    }
    try {
      const { blob, filename } = await api.exportChatSession(agent.id, turn.sessionId, "md");
      triggerBrowserDownload(blob, filename);
      turn.appendTurns(reply(t("exportDone", { filename })));
    } catch (err) {
      turn.appendTurns(reply(errorMessage(err), { failed: true }));
    }
  }, [agent, turn, reply, t]);

  const helpText = useCallback(
    () =>
      SLASH_COMMANDS.map((c) => `- \`/${c}${t(`cmdArg_${c}`) ? ` ${t(`cmdArg_${c}`)}` : ""}\` — ${t(`cmd_${c}`)}`).join(
        "\n"
      ),
    [t]
  );

  /**
   * Connect first: a request that acts on a service that is known not to be connected is held back with a
   * card that says so, instead of being sent to an agent that has no tool for it. True when it was held.
   */
  const holdForConnection = useCallback(
    async (message: string): Promise<boolean> => {
      const needs = detectNeeds(message);
      if (needs.length === 0) return false;
      const unmet = unmetNeeds(needs, await connections.refresh());
      if (unmet.length === 0) return false;
      const userTurnId = nextTurnId("u");
      turn.appendTurns(
        { id: userTurnId, role: "user", content: message, at: Date.now() },
        reply("", { connect: { needs: unmet, message, userTurnId } })
      );
      return true;
    },
    [connections, turn, reply]
  );

  const send = useCallback(
    async (
      text: string,
      opts: { skipGate?: boolean; /** The composer's "In background" mode: rung 2 without typing /background. */ background?: boolean } = {}
    ) => {
      if (!agent) return;
      const plan = dispatch(text, caps, { background: opts.background });
      switch (plan.rung) {
        case "empty":
          return;
        case "turn":
          if (!opts.skipGate && agent.isAssistant !== false && (await holdForConnection(plan.message))) return;
          await plainTurn(plan.message, {
            note: plan.degradedFrom ? t(`degraded_${plan.degradedFrom}`) : null,
          });
          return;
        case "job":
          await startJob(plan.goal, turnsRef.current, true);
          return;
        case "research":
          await startResearch(plan.question);
          return;
        case "schedule":
          await startSchedule(plan.spec, text);
          return;
        case "specialist": {
          const agents = await assistantApi.listAgents().catch((): AgentResponse[] => []);
          const wanted = plan.agentName.trim().toLowerCase();
          const match = agents.find((a) => (a.name ?? "").trim().toLowerCase() === wanted);
          if (!match) {
            await plainTurn(plan.message, { note: t("agentUnknown", { name: plan.agentName }) });
          } else {
            await plainTurn(plan.message, {
              targetAgentId: match.id,
              replyNote: t("answeringAs", { name: match.name }),
            });
          }
          return;
        }
        case "command":
          switch (plan.command) {
            case "new":
              onNewThread();
              return;
            case "export":
              await exportThread();
              return;
            case "help":
              turn.appendTurns(reply(`${t("helpTitle")}\n\n${helpText()}`));
              return;
            case "remember":
              turn.appendTurns({ id: nextTurnId("u"), role: "user", content: text, at: Date.now() });
              try {
                await assistantApi.rememberFact(plan.arg);
                turn.appendTurns(reply(t("remembered")));
              } catch (err) {
                turn.appendTurns(reply(errorMessage(err), { failed: true }));
              }
              return;
          }
      }
    },
    [
      agent,
      caps,
      holdForConnection,
      plainTurn,
      startJob,
      startResearch,
      startSchedule,
      exportThread,
      helpText,
      onNewThread,
      turn,
      reply,
      t,
    ]
  );

  /**
   * Send a held request for real: drop its echo and card, then run it as a normal turn. `force` skips the
   * connection check ("answer without it"); otherwise the status is read fresh and a request that is still
   * blocked keeps its card, marked so the person sees the check ran.
   */
  const resumeHeld = useCallback(
    async (cardId: string, force: boolean) => {
      const card = turnsRef.current.find((x) => x.id === cardId);
      if (!card?.connect || turn.sending) return;
      const { message, userTurnId, needs } = card.connect;
      if (!force && unmetNeeds(needs, await connections.refresh()).length > 0) {
        turn.setTurns((list) =>
          list.map((x) => (x.id === cardId && x.connect ? { ...x, connect: { ...x.connect, stillMissing: true } } : x))
        );
        return;
      }
      turn.setTurns((list) => list.filter((x) => x.id !== cardId && x.id !== userTurnId));
      // What was just connected brings tools the agent's allow-list does not have yet.
      if (agent && !force) await syncAssistantTools(assistantApi, agent.id);
      await send(message, { skipGate: true });
    },
    [turn, connections, send, agent]
  );

  // Connected in another tab and came back: carry on with the held request without being asked again.
  const autoResumed = useRef<string | null>(null);
  useEffect(() => {
    if (turn.sending) return;
    const held = [...turnsRef.current].reverse().find((x) => x.connect);
    if (!held?.connect || autoResumed.current === held.id) return;
    if (unmetNeeds(held.connect.needs, connections.status).length > 0) return;
    autoResumed.current = held.id;
    void resumeHeld(held.id, false);
  }, [connections.status, turn.sending, turn.turns, resumeHeld]);

  /** "Keep going in the background": the request that produced the reply at `index` (or the last one). */
  const continueInBackground = useCallback(
    async (index?: number) => {
      const list = turnsRef.current;
      const end = index ?? list.length - 1;
      let userIdx = end;
      while (userIdx >= 0 && list[userIdx].role !== "user") userIdx--;
      if (userIdx < 0) return;
      if (turn.sending) turn.cancel();
      await startJob(list[userIdx].content, list.slice(0, userIdx), false);
    },
    [turn, startJob]
  );

  return { send, continueInBackground, resumeHeld };
}

function formatWhen(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat(undefined, {
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
      day: "numeric",
      month: "short",
    }).format(d);
  } catch {
    return d.toLocaleString();
  }
}
