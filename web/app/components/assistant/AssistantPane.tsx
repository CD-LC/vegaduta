"use client";

import { useCallback, useEffect, useRef, useState, type DragEvent } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { AlertTriangle, FileUp, Loader2, PanelLeft, PanelRight } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { cn } from "../../lib/utils";
import { assistantApi } from "../../lib/api/assistant";
import { usePageReady } from "../RouteTransition";
import { Button } from "../ui/button";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { ONBOARDING_HOLD_ATTR } from "../../lib/overlayGate";
import { useComposerAttachments } from "../chat/useComposerAttachments";
import AssistantComposer from "./AssistantComposer";
import AssistantTranscript from "./AssistantTranscript";
import ThreadList from "./ThreadList";
import WorkRail from "./WorkRail";
import { useLearned } from "./useLearned";
import { transcriptToTurns } from "./threadIndex";
import { useAssistantAgent } from "./useAssistantAgent";
import { useAssistantDispatch, useDispatchCapabilities } from "./useAssistantDispatch";
import { useAssistantThreads } from "./useAssistantThreads";
import { useConnections } from "./useConnections";
import { useAssistantTurn } from "./useAssistantTurn";
import { useAssistantPrefs } from "./assistantPrefs";
import { useWarmLocalModel } from "../../lib/useWarmLocalModel";
import LocalModelTip from "./LocalModelTip";
import AssistantHeader from "./AssistantHeader";
import AssistantPalette from "./AssistantPalette";
import { useAssistantPaletteItems } from "./useAssistantPaletteItems";
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
import { sendOrLocalMedia } from "../../lib/edge/localMediaBridge";
import { useJobActions } from "./useJobActions";
import FolderPicker from "./FolderPicker";
import { useWorkspaceFolder } from "./useWorkspaceFolder";
import { useAssistantFiles } from "./useAssistantFiles";
import CameraCapture from "./CameraCapture";
import CallOverlay from "./CallOverlay";
import { useApprovalContinuation } from "../agents/useApprovalContinuation";
import { useActiveAgent } from "./useActiveAgent";
import { useQuickTask } from "./useQuickTask";
import ComposerModeBar from "./ComposerModeBar";
import { parseComposerMode, type ComposerMode } from "./assistantTypes";

/**
 * The /assistant screen: threads on the left (local index), the transcript and the box in the centre,
 * the work rail on the right. Composes the console's existing chat pieces rather than extending
 * ChatPanel; the behaviour lives in the hooks beside this file. Since 2026-10-18 it is also the
 * Playground (any agent, via the picker under the box), the Jobs launcher ("In background") and
 * Quick Task ("Quick task"), which used to be three pages.
 */
export default function AssistantPane() {
  const t = useTranslations("assistant");
  const tq = useTranslations("approvalsQueue");
  const searchParams = useSearchParams();
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
  const composerAttachments = useComposerAttachments(agent, turn.sending);
  const workspace = useWorkspaceFolder(ready);
  const livePreviews = usePreviews(ready && !!workspace.workspace?.terminal, `${workKey}:${turn.settledCount}`);
  const files = useAssistantFiles(composerAttachments, workspace.workspace, workspace.folder);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [callOpen, setCallOpen] = useState(false);
  const [dragging, setDragging] = useState(false);
  const callSession = useRef<string | null>(null);
  usePlatformBusyFlag("assistant-turn", turn.sending);
  const [input, setInput] = useState("");
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [browserOpen, setBrowserOpen] = useState(false);
  const [leftOpen, setLeftOpen] = useState(false);
  const [rightOpen, setRightOpen] = useState(false);
  const [prefs, setPrefs] = useAssistantPrefs();
  const speaking = useSpeaking();
  // Speech is a browser-wide queue that outlives the page: stop it when the setting turns off and when this screen goes away.
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

  // A task stops at every gated tool call (a command in an attached folder, an email, ...) for the person's
  // approval. Once they decide, core carries the task on by itself, with or without a folder: it starts a turn
  // for this thread that restates the request and delivers the tool's result. The pane sends nothing; it follows
  // that turn through the stored thread, keeps the box off meanwhile, and appends the reply.
  const approvalContinuation = useApprovalContinuation({
    agentId: active?.id ?? null,
    sessionId: turn.sessionId,
    onReply: (reply) => {
      turn.appendTurns({
        id: nextTurnId("a"),
        role: "assistant",
        content: reply.content,
        at: Date.now(),
        ...(reply.failed ? { failed: true } : {}),
      });
      setWorkKey((n) => n + 1);
    },
    // "Ask it to carry on" under a continuation that did not start, timed out or broke: an ordinary message.
    onRetry: () => void dispatcher.send(tq("retryMessage")),
  });

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
    void sendOrLocalMedia(message, (m) => dispatcher.send(m, { background: mode === "background" }), (u, a) =>
      turn.appendTurns(
        { id: nextTurnId("u"), role: "user", content: u, at: Date.now() },
        { id: nextTurnId("a"), role: "assistant", content: a, at: Date.now() },
      ),
    );
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

  const bumpWork = useCallback(() => setWorkKey((n) => n + 1), []);
  const { showJobResult, stopJob, stoppingId } = useJobActions({
    appendTurns: turn.appendTurns,
    onError: setThreadError,
    onChanged: bumpWork,
  });

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

  const paletteItems = useAssistantPaletteItems({
    agentId: active?.id ?? null,
    jobs: caps.jobs,
    onNewThread: newThread,
    onMode: setMode,
    onPrompt: setInput,
  });

  // Wide screens show both panels inline and the button folds them away (remembered per browser);
  // narrow screens keep them as overlays the same button opens.
  const toggleRail = (side: "left" | "right") => {
    const wide = window.matchMedia(side === "left" ? "(min-width: 1024px)" : "(min-width: 1280px)").matches;
    if (wide)
      setPrefs(side === "left" ? { leftCollapsed: !prefs.leftCollapsed } : { rightCollapsed: !prefs.rightCollapsed });
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
          className="absolute -end-0.5 -top-0.5 grid size-4 place-items-center rounded-full bg-primary text-[10px] font-semibold text-primary-foreground"
          data-testid="work-badge"
        >
          {liveTasks}
        </span>
      )}
    </Button>
  );

  // First-run overlays (the welcome modal, the tour) never land on a conversation: someone with threads, or with
  // one open, is not new here (lib/overlayGate reads this; "pending" makes it wait until the threads are known).
  const onboardingHold = !threads.loaded
    ? "pending"
    : threads.threads.length > 0 || turn.turns.length > 0
      ? "returning"
      : "first-run";
  const overlayOpen = leftOpen || rightOpen;
  useEffect(() => {
    if (!overlayOpen) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setLeftOpen(false);
      setRightOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [overlayOpen]);

  return (
    <div
      className="relative flex min-h-0 flex-1"
      data-testid="assistant-pane"
      {...{ [ONBOARDING_HOLD_ATTR]: onboardingHold }}
      data-turn-in-flight={turn.sending || approvalContinuation.continuing ? "true" : undefined}
      onDragOver={onDragOver}
      onDragLeave={(e) => {
        if (e.currentTarget === e.target) setDragging(false);
      }}
      onDrop={onDrop}
    >
      {/* Narrow screens: a panel opened over the conversation dims it, and a tap outside closes the panel. */}
      {overlayOpen && (
        <button
          type="button"
          aria-label={t("closePanel")}
          className={cn(
            "absolute inset-0 z-10 cursor-default border-0 bg-background/60 p-0 shadow-none backdrop-blur-[2px]",
            rightOpen ? "xl:hidden" : "lg:hidden"
          )}
          onClick={() => {
            setLeftOpen(false);
            setRightOpen(false);
          }}
          data-testid="assistant-panel-scrim"
        />
      )}
      <aside
        className={cn(
          "asst-panel-left w-64 shrink-0 border-e border-border",
          leftOpen
            ? "absolute inset-y-0 start-0 z-20 flex flex-col shadow-lg lg:static"
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
        <AssistantHeader
          agent={active ? { id: active.id, name: active.name, isAssistant: active.isAssistant } : null}
          justCreated={agentState.status === "ready" && !!agentState.created}
          busy={turn.sending || liveTasks > 0 || approvalContinuation.continuing}
          prefs={prefs}
          setPrefs={setPrefs}
          speaking={speaking}
          previews={{
            count: livePreviews.previews.length,
            visible: previewVisible,
            onToggle: () => setPreviewOpen((o) => !o),
          }}
          browser={{
            ready: browserReady,
            visible: browserVisible,
            pending: !!browser.pending,
            onToggle: () => setBrowserOpen((o) => !o),
          }}
          sessionId={turn.sessionId}
          leftRail={railButton("left")}
          rightRail={railButton("right")}
        />

        {active && <LocalModelTip agentId={active.id} providerName={active.providerName} />}
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
          task={{
            onConfirm: (id) => void quick.confirm(id),
            onCancel: quick.cancel,
            onChannel: quick.setChannel,
            onSavePhone: quick.savePhone,
          }}
          sessionId={turn.sessionId}
          canBackground={caps.jobs && ready}
          restoredNote={restoredNote}
          onRetry={turn.retry}
          onAction={(message) => void dispatcher.send(message)}
          onResumeHeld={(id, force) => void dispatcher.resumeHeld(id, force)}
          onBackground={(index) => void dispatcher.continueInBackground(index)}
          onSuggestion={setInput}
          approvalContinuation={approvalContinuation}
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
          disabled={!ready || approvalContinuation.continuing}
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
      {cameraOpen && (
        <CameraCapture onCapture={(file) => void files.pick([file])} onClose={() => setCameraOpen(false)} />
      )}
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
        <PreviewPane
          previews={livePreviews.previews}
          onStop={(port) => void livePreviews.stop(port)}
          onClose={() => setPreviewOpen(false)}
        />
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
          "asst-panel-right w-72 shrink-0 border-s border-border",
          rightOpen
            ? "absolute inset-y-0 end-0 z-20 flex flex-col shadow-lg xl:static"
            : prefs.rightCollapsed
              ? "hidden"
              : "hidden xl:static xl:flex xl:flex-col"
        )}
      >
        <WorkRail
          counts={counts}
          onStopJob={(job) => void stopJob(job)}
          onShowJob={(job) => void showJobResult(job)}
          stoppingId={stoppingId}
          learned={learned}
        />
      </aside>
    </div>
  );
}
