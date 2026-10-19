"use client";

import { useCallback, useEffect, useRef, useState, type DragEvent } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { AlertTriangle, Brain, Globe, FileUp, Hammer, Loader2, Monitor, MessageSquarePlus, PanelLeft, PanelRight, Sparkles, SlidersHorizontal, Workflow, Zap, Volume2, VolumeX, Square } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { cn } from "../../lib/utils";
import { assistantApi } from "../../lib/api/assistant";
import { usePageReady } from "../RouteTransition";
import { Button } from "../ui/button";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import ChatExportMenu from "../ChatExportMenu";
import { useComposerAttachments } from "../chat/useComposerAttachments";
import AssistantComposer from "./AssistantComposer";
import AssistantTranscript from "./AssistantTranscript";
import ThreadList from "./ThreadList";
import WorkRail from "./WorkRail";
import { useLearned } from "./useLearned";
import { transcriptToTurns } from "./threadIndex";
import { approvalOutcomeLines } from "./needs";
import { useAssistantAgent } from "./useAssistantAgent";
import { useAssistantDispatch, useDispatchCapabilities } from "./useAssistantDispatch";
import { useAssistantThreads } from "./useAssistantThreads";
import { useConnections } from "./useConnections";
import { useAssistantTurn } from "./useAssistantTurn";
import { useAssistantPrefs } from "./assistantPrefs";
import { useWarmLocalModel } from "../../lib/useWarmLocalModel";
import AssistantInfoCard from "./AssistantInfoCard";
import AssistantPalette, { type PaletteItem } from "./AssistantPalette";
import { usePlatformBusyFlag } from "../../lib/platformBusy";
import PreviewPane from "./PreviewPane";
import BrowserPane from "./BrowserPane";
import { stopSpeaking } from "./speakText";
import { useSpeaking } from "./useSpeaking";
import { useAssistantBrowser } from "./useAssistantBrowser";
import { usePreviews } from "./usePreviews";
import AssistantStatusBar from "./AssistantStatusBar";
import { useWorkCounts } from "./useWorkCounts";
import { useJobFinish } from "./useJobFinish";
import {
  forgetThreadTasks,
  handoffTurns,
  isJobThread,
  jobThreadTurns,
  JOB_THREAD_PREFIX,
  backgroundTasks,
  isLivePhase,
  jobPhase,
  recordBackgroundTask,
  taskForJob,
} from "./backgroundWork";
import { nextTurnId } from "./useAssistantTurn";
import type { AgentJobSummaryResponse } from "../../lib/api/types";
import FolderPicker from "./FolderPicker";
import { useWorkspaceFolder } from "./useWorkspaceFolder";
import { useAssistantFiles } from "./useAssistantFiles";
import CameraCapture from "./CameraCapture";
import CallOverlay from "./CallOverlay";
import type { ToolApprovalOutcome } from "../agents/toolApprovalMatching";
import { useActiveAgent } from "./useActiveAgent";
import { useQuickTask } from "./useQuickTask";
import ComposerModeBar from "./ComposerModeBar";
import { parseComposerMode, type ComposerMode } from "./assistantTypes";

/** A header toggle that is switched on: solid brand fill and a glow ring, so on and off are never confused. */
const TOGGLE_ON = "bg-primary text-primary-foreground ring-4 ring-primary/30 hover:bg-primary/90 hover:text-primary-foreground";

/**
 * The /assistant screen: threads on the left (local index), the transcript and the box in the centre,
 * the work rail on the right. Composes the console's existing chat pieces rather than extending
 * ChatPanel; the behaviour lives in the hooks beside this file. Since 2026-10-18 it is also the
 * Playground (any agent, via the picker under the box), the Jobs launcher ("In background") and
 * Quick Task ("Quick task"), which used to be three pages.
 */
export default function AssistantPane() {
  const t = useTranslations("assistant");
  const tb = useTranslations("assistantBrowser");
  const searchParams = useSearchParams();
  const router = useRouter();
  const agentState = useAssistantAgent();
  const assistantAgent = agentState.status === "ready" ? agentState.agent : null;
  // Who answers: the Assistant by default, or the agent picked under the box / in ?agent=.
  const picker = useActiveAgent(assistantAgent, searchParams.get("agent"));
  const active = picker.active;
  // "Auto" in the picker (Quick Task only): the server picks the specialist.
  const [autoAgent, setAutoAgent] = useState(false);
  const [mode, setMode] = useState<ComposerMode>(() => parseComposerMode(searchParams.get("mode")));
  const agent = active ? (picker.agents.find((a) => a.id === active.id) ?? assistantAgent) : null;
  const ready = agent !== null && active !== null;
  usePageReady(agentState.status !== "loading");

  const turn = useAssistantTurn(active?.id ?? null);
  const threads = useAssistantThreads();
  const caps = useDispatchCapabilities(ready);
  const connections = useConnections(ready);
  const [workKey, setWorkKey] = useState(0);
  const counts = useWorkCounts(ready, `${workKey}:${turn.settledCount}`, active?.id ?? null);
  const learned = useLearned(ready, turn.settledCount);
  const [stoppingId, setStoppingId] = useState<string | null>(null);
  const composerAttachments = useComposerAttachments(agent, turn.sending);
  const workspace = useWorkspaceFolder(ready);
  const livePreviews = usePreviews(ready && !!workspace.workspace?.terminal, `${workKey}:${turn.settledCount}`);
  const files = useAssistantFiles(composerAttachments, workspace.workspace, workspace.folder);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [callOpen, setCallOpen] = useState(false);
  const [dragging, setDragging] = useState(false);
  const callSession = useRef<string | null>(null);
  usePlatformBusyFlag("assistant-turn", turn.sending);
  const sendingRef = useRef(false);
  sendingRef.current = turn.sending;
  const [input, setInput] = useState("");
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [browserOpen, setBrowserOpen] = useState(false);
  const [leftOpen, setLeftOpen] = useState(false);
  const [rightOpen, setRightOpen] = useState(false);
  const [prefs, setPrefs] = useAssistantPrefs();
  const speaking = useSpeaking();
  // Speech is a browser-wide queue that outlives the page: stop it when the setting turns off (also from
  // another tab) and when this screen goes away.
  useEffect(() => {
    if (!prefs.speakReplies) stopSpeaking();
  }, [prefs.speakReplies]);
  useEffect(() => () => stopSpeaking(), []);
  // Load the local model (if this install has one) while the user is still reading, not on first send.
  useWarmLocalModel();
  const [restoredNote, setRestoredNote] = useState<string | null>(null);
  const [threadError, setThreadError] = useState<string | null>(null);

  // Arriving from an old route: ?prompt= prefills the box (the Playground's deep link), ?from= says
  // in one line where the page went. Read once; the address bar is not re-read on re-render.
  const seededRef = useRef(false);
  useEffect(() => {
    if (seededRef.current) return;
    seededRef.current = true;
    const prompt = searchParams.get("prompt");
    if (prompt) setInput(prompt.slice(0, 600));
    const from = searchParams.get("from");
    if (from === "playground" || from === "jobs" || from === "quickTask") setRestoredNote(t(`redirectedFrom_${from}`));
  }, [searchParams, t]);

  const newThread = useCallback(() => {
    if (turn.sending) return;
    turn.reset(null, []);
    setRestoredNote(null);
    setThreadError(null);
    setLeftOpen(false);
  }, [turn]);

  const onSettled = useCallback(
    (sessionId: string | null, firstUserLine: string) => {
      setWorkKey((n) => n + 1);
      if (sessionId && active) void threads.noteActivity(sessionId, active.id, firstUserLine);
    },
    [active, threads]
  );

  const quick = useQuickTask(turn, (line) => onSettled(turn.sessionId, line));

  const dispatcher = useAssistantDispatch({
    agent: active ? { id: active.id, name: active.name, isAssistant: active.isAssistant } : null,
    turn,
    caps,
    connections,
    attachments: {
      items: composerAttachments.attachments,
      clear: () => composerAttachments.setAttachments([]),
      restore: composerAttachments.setAttachments,
    },
    onSettled,
    onBackgroundStarted: (task) => recordBackgroundTask(threads.scope, task),
    onNewThread: newThread,
    folder: workspace.latest,
  });

  // A long task in an attached folder stops at every command for the person's approval; once they
  // decide, the Assistant is told to carry on, so "build this project" keeps going without a
  // "continue" message after each command. Ordinary chat (no folder) is left as it was.
  const onApprovalDecided = useCallback(
    (outcome: ToolApprovalOutcome) => {
      if (!workspace.latest.current || sendingRef.current) return;
      void dispatcher.send(outcome.kind === "rejected" ? t("continueAfterReject") : t("continueAfterApproval"), {
        contextLines: approvalOutcomeLines(outcome),
      });
    },
    [workspace.latest, dispatcher, t]
  );

  const openThread = useCallback(
    async (id: string, force = false) => {
      if (!active || turn.sending || (id === turn.sessionId && !force)) {
        setLeftOpen(false);
        return;
      }
      setThreadError(null);
      setLeftOpen(false);
      // A thread belongs to the agent it was had with; opening it switches the box to that agent.
      const owner = threads.threads.find((x) => x.id === id)?.agentId ?? active.id;
      if (owner !== active.id) picker.select(owner);
      if (isJobThread(id)) {
        // A thread that began as a hand-off: the request and its card are all there is locally.
        const task = taskForJob(threads.scope, id.slice(JOB_THREAD_PREFIX.length));
        turn.reset(null, task ? jobThreadTurns(task) : []);
        setRestoredNote(task ? t("restoredJob") : t("restoredEmpty"));
        return;
      }
      try {
        const transcript = await assistantApi.getChatTranscript(owner, id);
        const restored = [
          ...transcriptToTurns(transcript, id),
          ...handoffTurns(backgroundTasks(threads.scope).filter((x) => x.threadId === id)),
        ];
        turn.reset(id, restored);
        setRestoredNote(transcript ? t("restoredNote") : t("restoredEmpty"));
      } catch (err) {
        setThreadError(errorMessage(err, t("threadLoadFailed")));
      }
    },
    [active, picker, turn, t, threads.scope, threads.threads]
  );

  const send = useCallback(() => {
    const text = input;
    // ChatRequest.message is @NotBlank server-side: an attachment-only send still needs text.
    const message = text.trim() || (composerAttachments.attachments.length > 0 ? t("attachmentOnlyMessage") : "");
    if (!message) return;
    setInput("");
    setRestoredNote(null);
    if (mode === "task") {
      void quick.start(message, autoAgent ? null : (active?.id ?? null));
      return;
    }
    void dispatcher.send(message, { background: mode === "background" });
  }, [input, composerAttachments.attachments.length, dispatcher, t, mode, quick, autoAgent, active?.id]);

  const selectAgent = useCallback(
    (id: string) => {
      if (turn.sending) return;
      setAutoAgent(id === "");
      if (id) picker.select(id);
      // Another agent, another conversation: a thread is had with one agent.
      if (id && id !== active?.id) turn.reset(null, []);
    },
    [turn, picker, active?.id]
  );

  const canTakeFiles = ready && !turn.sending && (files.searchable || !!composerAttachments.modalities);
  const hasFiles = (e: DragEvent) => Array.from(e.dataTransfer?.types ?? []).includes("Files");
  const onDragOver = (e: DragEvent) => {
    if (!canTakeFiles || !hasFiles(e)) return;
    e.preventDefault();
    setDragging(true);
  };
  const onDrop = (e: DragEvent) => {
    if (!hasFiles(e)) return;
    e.preventDefault();
    setDragging(false);
    if (canTakeFiles) void files.pick(Array.from(e.dataTransfer.files));
  };

  // What was said in a call is stored in the Assistant's session; show it once the call is over.
  const closeCall = useCallback(() => {
    setCallOpen(false);
    const id = callSession.current ?? turn.sessionId;
    callSession.current = null;
    if (id) void openThread(id, true);
  }, [openThread, turn.sessionId]);

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
        turn.appendTurns({ id: nextTurnId("a"), role: "assistant", content: text, at: Date.now(), note, failed: phase === "failed" });
      } catch (err) {
        turn.appendTurns({ id: nextTurnId("a"), role: "assistant", content: errorMessage(err), at: Date.now(), failed: true });
      }
    },
    [turn, t]
  );

  const stopJob = useCallback(
    async (job: AgentJobSummaryResponse) => {
      setStoppingId(job.id);
      try {
        await assistantApi.cancelJob(job.agentId, job.id);
      } catch (err) {
        setThreadError(errorMessage(err, t("taskStopFailed")));
      } finally {
        setStoppingId(null);
        setWorkKey((n) => n + 1);
      }
    },
    [t]
  );

  // A task started from the conversation on screen finishes: say so right there, with its outcome.
  const turnsRef = useRef(turn.turns);
  turnsRef.current = turn.turns;
  useJobFinish(counts.jobs, threads.scope, (job) => {
    const onScreen = turnsRef.current.some((x) => x.handoff?.id === job.id);
    if (onScreen) void showJobResult(job);
  });

  // The Preview pane opens only when the person asks (its button shows while a server is running): a
  // pane that appears by itself squeezes the chat to a sliver.
  const previewVisible = previewOpen && livePreviews.previews.length > 0;

  // The browser pane opens by itself only when the agent (or a pause) is waiting on the person, once per request.
  const browser = useAssistantBrowser(active?.id, turn.sessionId, browserOpen);
  const seenTakeover = useRef<string | null>(null);
  useEffect(() => {
    const id = browser.pending?.takeoverId ?? null;
    if (id && id !== seenTakeover.current) {
      seenTakeover.current = id;
      setBrowserOpen(true);
    }
  }, [browser.pending]);
  // Offered only where it works: an install without the browser add-on shows no button and no pane.
  const browserReady = browser.availability === "ready";
  const browserVisible = browserOpen && browserReady;

  const liveTasks = counts.jobs.filter((j) => isLivePhase(jobPhase(j.status))).length;

  // Cmd/Ctrl+K opens the palette from anywhere on the page.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((o) => !o);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const paletteItems: PaletteItem[] = [
    { id: "new", group: t("paletteGroupActions"), label: t("paletteNewThread"), icon: <MessageSquarePlus size={14} />, run: newThread },
    { id: "mode-chat", group: t("paletteGroupActions"), label: t("statusMode_chat"), icon: <Sparkles size={14} />, run: () => setMode("chat") },
    ...(caps.jobs
      ? [{ id: "mode-bg", group: t("paletteGroupActions"), label: t("statusMode_background"), icon: <Workflow size={14} />, run: () => setMode("background") }]
      : []),
    { id: "mode-task", group: t("paletteGroupActions"), label: t("statusMode_task"), icon: <Zap size={14} />, run: () => setMode("task") },
    {
      id: "tune",
      group: t("paletteGroupBuild"),
      label: t("journey_tune_title"),
      hint: t("paletteOpens"),
      icon: <SlidersHorizontal size={14} />,
      run: () => router.push(active ? `/agents?agent=${encodeURIComponent(active.id)}` : "/agents"),
    },
    { id: "workflow", group: t("paletteGroupBuild"), label: t("journey_workflow_title"), hint: t("paletteOpens"), icon: <Workflow size={14} />, run: () => router.push("/workflows") },
    { id: "app", group: t("paletteGroupBuild"), label: t("journey_app_title"), hint: t("paletteOpens"), icon: <Hammer size={14} />, run: () => router.push("/build") },
    ...(["tune", "workflow", "app"] as const).map((j) => ({
      id: `ask-${j}`,
      group: t("paletteGroupAsk"),
      label: t(`journey_${j}_blurb`),
      icon: <Sparkles size={14} />,
      run: () => setInput(t(`journey_${j}_prompt`)),
    })),
  ];

  // Wide screens show both panels inline and the button folds them away (remembered per browser);
  // narrow screens keep them as overlays the same button opens.
  const toggleRail = (side: "left" | "right") => {
    const wide = window.matchMedia(side === "left" ? "(min-width: 1024px)" : "(min-width: 1280px)").matches;
    if (wide) setPrefs(side === "left" ? { leftCollapsed: !prefs.leftCollapsed } : { rightCollapsed: !prefs.rightCollapsed });
    else if (side === "left") setLeftOpen((o) => !o);
    else setRightOpen((o) => !o);
  };
  const railButton = (side: "left" | "right") => (
    <Button
      type="button"
      size="icon"
      variant="ghost"
      className="relative"
      aria-label={side === "left" ? t("threadsLabel") : t("workLabel")}
      aria-expanded={side === "left" ? leftOpen || !prefs.leftCollapsed : rightOpen || !prefs.rightCollapsed}
      title={side === "left" ? t("threadsLabel") : t("workLabel")}
      onClick={() => toggleRail(side)}
    >
      {side === "left" ? <PanelLeft size={16} aria-hidden="true" /> : <PanelRight size={16} aria-hidden="true" />}
      {side === "right" && liveTasks > 0 && (
        <span
          className="absolute -right-0.5 -top-0.5 grid size-4 place-items-center rounded-full bg-primary text-[10px] font-semibold text-primary-foreground"
          data-testid="work-badge"
        >
          {liveTasks}
        </span>
      )}
    </Button>
  );

  return (
    <div
      className="relative flex min-h-0 flex-1"
      data-testid="assistant-pane"
      onDragOver={onDragOver}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) setDragging(false);
      }}
      onDrop={onDrop}
    >
      <aside
        className={cn(
          "asst-panel-left w-64 shrink-0 border-r border-border",
          leftOpen
            ? "absolute inset-y-0 left-0 z-20 flex flex-col shadow-lg lg:static"
            : prefs.leftCollapsed
              ? "hidden"
              : "hidden lg:static lg:flex lg:flex-col"
        )}
      >
        <ThreadList
          threads={threads.threads}
          loaded={threads.loaded}
          activeId={turn.sessionId}
          busy={turn.sending}
          onSelect={(id) => void openThread(id)}
          onNew={newThread}
          onTogglePin={(id) => void threads.togglePinned(id)}
          onRename={(id, title) => void threads.rename(id, title)}
          onRemove={(id) => {
            void threads.remove(id);
            forgetThreadTasks(threads.scope, id);
            if (id === turn.sessionId) newThread();
          }}
        />
      </aside>

      <section className="asst-canvas flex min-w-0 flex-1 flex-col">
        <header className="asst-header flex items-center gap-2 border-b border-border px-3 py-2 sm:px-6">
          {railButton("left")}
          <Sparkles
            className={cn("size-4 shrink-0 text-primary", (turn.sending || liveTasks > 0) && "asst-mark-busy")}
            aria-hidden="true"
            data-testid="assistant-mark"
            data-busy={turn.sending || liveTasks > 0 ? "true" : undefined}
          />
          <h2 className="truncate text-sm font-semibold text-foreground">{active?.name ?? t("title")}</h2>
          {active && !active.isAssistant && (
            <span className="truncate rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground" data-testid="talking-to">
              {t("answeringAgent", { name: active.name })}
            </span>
          )}
          {agentState.status === "ready" && agentState.created && (
            <span className="rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">
              {t("justCreated")}
            </span>
          )}
          <span className="flex-1" />
          <AssistantInfoCard />
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-pressed={prefs.speakReplies}
            aria-label={prefs.speakReplies ? t("speakRepliesOff") : t("speakRepliesOn")}
            title={prefs.speakReplies ? t("speakRepliesOff") : t("speakRepliesOn")}
            className={cn(prefs.speakReplies && TOGGLE_ON)}
            onClick={() => {
              // Off means silent now: speech already queued in the browser would otherwise play on.
              if (prefs.speakReplies) stopSpeaking();
              setPrefs({ speakReplies: !prefs.speakReplies });
            }}
          >
            {prefs.speakReplies ? <Volume2 size={16} aria-hidden="true" /> : <VolumeX size={16} aria-hidden="true" />}
          </Button>
          {speaking && (
            <Button type="button" size="sm" variant="destructive" onClick={() => stopSpeaking()} aria-label={t("stopSpeakingNow")} title={t("stopSpeakingNow")} data-testid="stop-speaking">
              <Square size={12} aria-hidden="true" />
              <span className="hidden sm:inline">{t("stopSpeakingNow")}</span>
            </Button>
          )}
          <Button
            type="button"
            size="icon"
            variant="ghost"
            aria-pressed={prefs.showThinking}
            aria-label={prefs.showThinking ? t("hideThinking") : t("showThinking")}
            title={prefs.showThinking ? t("hideThinking") : t("showThinking")}
            className={cn(prefs.showThinking && TOGGLE_ON)}
            onClick={() => setPrefs({ showThinking: !prefs.showThinking })}
          >
            <Brain size={16} aria-hidden="true" />
          </Button>
          {livePreviews.previews.length > 0 && (
            <Button type="button" size="sm" variant={previewVisible ? "secondary" : "outline"} onClick={() => setPreviewOpen((o) => !o)} aria-label={t("previewTitle")} title={t("previewTitle")} data-testid="preview-toggle">
              <Monitor size={14} aria-hidden="true" />
              <span className="hidden sm:inline">{t("previewTitle")}</span>
            </Button>
          )}
          {browserReady && (
            <Button type="button" size="sm" variant={browserVisible ? "secondary" : "outline"} onClick={() => setBrowserOpen((o) => !o)} aria-pressed={browserVisible} aria-label={tb("toggle")} title={tb("toggle")} data-testid="browser-toggle">
              <Globe size={14} aria-hidden="true" />
              <span className="hidden sm:inline">{tb("toggle")}</span>
              {browser.pending && !browserVisible && <span className="size-2 rounded-full bg-amber-500" role="status" aria-label={tb("needsYou")} />}
            </Button>
          )}
          {active && <ChatExportMenu agentId={active.id} sessionId={turn.sessionId} />}
          {active && (
            <Link
              href={`/agents?agent=${encodeURIComponent(active.id)}`}
              className="hidden whitespace-nowrap text-xs text-muted-foreground underline-offset-2 hover:underline sm:inline"
            >
              {t("openAgent")}
            </Link>
          )}
          {railButton("right")}
        </header>

        {agentState.status === "loading" && (
          <p className="flex items-center gap-2 px-6 py-3 text-sm text-muted-foreground" role="status">
            <Loader2 size={14} className="animate-spin" aria-hidden="true" />
            {t("settingUp")}
          </p>
        )}
        {agentState.status === "no-model" && (
          <Alert className="m-4">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>{t("noModelTitle")}</AlertTitle>
            <AlertDescription>
              <p>{agentState.message || t("noModelBody")}</p>
              <div className="flex gap-2">
                <Button asChild size="sm">
                  <Link href="/providers">{t("noModelAction")}</Link>
                </Button>
                <Button size="sm" variant="outline" onClick={agentState.retry}>
                  {t("retry")}
                </Button>
              </div>
            </AlertDescription>
          </Alert>
        )}
        {agentState.status === "error" && (
          <Alert variant="destructive" className="m-4">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>{t("setupFailedTitle")}</AlertTitle>
            <AlertDescription>
              <p>{agentState.message}</p>
              <Button size="sm" variant="outline" onClick={agentState.retry}>
                {t("retry")}
              </Button>
            </AlertDescription>
          </Alert>
        )}
        {(turn.error || threadError) && (
          <div
            role="alert"
            className="mx-4 mt-3 rounded-lg border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {threadError ?? turn.error}
          </div>
        )}

        <AssistantTranscript
          turns={turn.turns}
          sending={turn.sending}
          agentId={active?.id ?? null}
          agentName={active?.name ?? t("title")}
          task={{ onConfirm: (id) => void quick.confirm(id), onCancel: quick.cancel, onChannel: quick.setChannel }}
          sessionId={turn.sessionId}
          canBackground={caps.jobs && ready}
          restoredNote={restoredNote}
          onRetry={turn.retry}
          onAction={(message) => void dispatcher.send(message)}
          onResumeHeld={(id, force) => void dispatcher.resumeHeld(id, force)}
          onBackground={(index) => void dispatcher.continueInBackground(index)}
          onSuggestion={setInput}
          onApprovalDecided={onApprovalDecided}
          showThinking={prefs.showThinking}
          speakReplies={prefs.speakReplies}
        />

        <FolderPicker
          workspace={workspace.workspace}
          folder={workspace.folder}
          onAttach={workspace.attach}
          onCreate={workspace.create}
          disabled={turn.sending}
        />

        <AssistantComposer
          value={input}
          onChange={setInput}
          onSend={send}
          onStop={turn.cancel}
          onBackground={caps.jobs && ready ? () => void dispatcher.continueInBackground() : null}
          sending={turn.sending}
          disabled={!ready}
          attachments={{
            modalities: composerAttachments.modalities,
            searchable: files.searchable,
            items: files.items,
            error: files.error,
            attaching: files.attaching,
            onFiles: (picked) => void files.pick(picked),
            onRemove: files.remove,
          }}
          snapshot={turn.snapshot}
          reconnecting={turn.reconnecting}
          onCamera={ready ? () => setCameraOpen(true) : null}
          onCall={ready ? () => setCallOpen(true) : null}
          placeholder={
            mode === "background"
              ? t("composerPlaceholderBackground")
              : mode === "task"
                ? t("composerPlaceholderTask")
                : active && !active.isAssistant
                  ? t("composerPlaceholderAgent", { name: active.name })
                  : undefined
          }
          toolbar={
            active && assistantAgent ? (
              <ComposerModeBar
                agents={picker.agents}
                activeAgentId={autoAgent && mode === "task" ? "" : active.id}
                assistantId={assistantAgent.id}
                onSelectAgent={selectAgent}
                mode={mode}
                onMode={setMode}
                modes={{ background: caps.jobs, task: true }}
                disabled={!ready || turn.sending}
              />
            ) : null
          }
        />
        <AssistantStatusBar
          agentName={active?.name ?? null}
          mode={mode}
          folderName={workspace.folder}
          liveTasks={liveTasks}
          sending={turn.sending}
          onOpenPalette={() => setPaletteOpen(true)}
        />
      </section>

      {dragging && (
        <div
          className="pointer-events-none absolute inset-2 z-30 grid place-items-center rounded-3xl border-2 border-dashed border-primary/60 bg-background/85 backdrop-blur-sm"
          data-testid="assistant-drop"
        >
          <div className="flex flex-col items-center gap-2 text-center">
            <span className="grid size-12 place-items-center rounded-full bg-primary/10 text-primary">
              <FileUp size={22} aria-hidden="true" />
            </span>
            <p className="text-sm font-semibold text-foreground">{t("dropTitle")}</p>
            <p className="text-xs text-muted-foreground">{files.searchable ? t("dropSearchable") : t("dropPlain")}</p>
          </div>
        </div>
      )}

      {paletteOpen && <AssistantPalette items={paletteItems} onClose={() => setPaletteOpen(false)} />}
      {cameraOpen && <CameraCapture onCapture={(file) => void files.pick([file])} onClose={() => setCameraOpen(false)} />}
      {callOpen && agent && (
        <CallOverlay
          agent={agent}
          sessionId={turn.sessionId}
          onSessionId={(id) => {
            callSession.current = id;
          }}
          onClose={closeCall}
        />
      )}

      {previewVisible && (
        <PreviewPane previews={livePreviews.previews} onStop={(port) => void livePreviews.stop(port)} onClose={() => setPreviewOpen(false)} />
      )}

      {browserVisible && (
        <BrowserPane
          availability={browser.availability}
          agentId={active?.id}
          sessionId={turn.sessionId}
          profile={browser.profile}
          profilesOn={browser.profilesOn}
          onClose={() => setBrowserOpen(false)}
        />
      )}

      <aside
        className={cn(
          "asst-panel-right w-72 shrink-0 border-l border-border",
          rightOpen
            ? "absolute inset-y-0 right-0 z-20 flex flex-col shadow-lg xl:static"
            : prefs.rightCollapsed
              ? "hidden"
              : "hidden xl:static xl:flex xl:flex-col"
        )}
      >
        <WorkRail counts={counts} onStopJob={(job) => void stopJob(job)} onShowJob={(job) => void showJobResult(job)} stoppingId={stoppingId} learned={learned} />
      </aside>
    </div>
  );
}
