"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import dynamic from "next/dynamic";
import { Loader2, MessageCircle, Mic, Send, Square, Volume2 } from "lucide-react";

import RichText from "../../components/RichText";
import { GenerativeUiRenderer } from "../../components/generative-ui/GenerativeUiRenderer";
import { extractUiSpec } from "../../components/generative-ui/extractUiSpec";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Textarea } from "../../components/ui/textarea";
import { api } from "../../lib/api";
import type { TutorAgentResult, TutorLesson, TutorPlan } from "../../lib/api/tutorTypes";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { speakReply } from "../../lib/voice/speakReply";
import { stopAllSpeech } from "../../lib/voice/voicePlayers";
import { languageLabel } from "./PlanView";
import { buildLessonContext, clipChatMessage, composeFirstMessage, plainTextForSpeech } from "./lessonContext";
import { wrongQuestions } from "./tutorState";

// The call panel pulls in the WebRTC client; it is loaded only when the
// learner presses Talk, never with the lesson itself.
const CallPanel = dynamic(() => import("../../components/agents/CallPanel"), { ssr: false });

/**
 * "Listen": reads the lesson explanation aloud through the shared voice chain
 * (an installed on-device voice, else the browser's own). It never downloads
 * a voice by itself. Pressing again stops it.
 */
export function LessonListenButton({ text }: { text: string }) {
  const t = useTranslations("tutorPage");
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const runRef = useRef(0);
  const [speaking, setSpeaking] = useState(false);
  const [silent, setSilent] = useState(false);

  useEffect(() => {
    const el = audioRef;
    const run = runRef;
    return () => {
      run.current += 1;
      stopAllSpeech(el.current);
    };
  }, []);

  async function toggle() {
    if (speaking) {
      runRef.current += 1;
      stopAllSpeech(audioRef.current);
      setSpeaking(false);
      return;
    }
    const run = ++runRef.current;
    setSpeaking(true);
    setSilent(false);
    const result = await speakReply({
      text: plainTextForSpeech(text),
      getAudioEl: () => audioRef.current,
      isStale: () => runRef.current !== run,
    });
    if (runRef.current !== run) return;
    setSpeaking(false);
    if (!result.spokenBy && !result.aborted) setSilent(true);
  }

  return (
    <span className="inline-flex flex-wrap items-center gap-2">
      <Button type="button" variant="outline" size="sm" onClick={() => void toggle()} data-testid="tutor-listen">
        {speaking ? <Square aria-hidden="true" /> : <Volume2 aria-hidden="true" />}
        {speaking ? t("listenStop") : t("listen")}
      </Button>
      {silent && (
        <span className="text-xs text-muted-foreground" role="status">
          {t("listenUnavailable")}
        </span>
      )}
      <audio ref={audioRef} className="hidden" />
    </span>
  );
}

type Turn = { role: "user" | "assistant"; text: string };

/** The slice of the agent row the chat and the call panel read. */
type TutorAgentRow = {
  id: string;
  name?: string;
  providerName?: string | null;
  model?: string | null;
  systemPrompt?: string | null;
  instructions?: string | null;
};

type AgentState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; agent: TutorAgentRow }
  | { kind: "error"; message: string };

const STARTER_KEYS = ["chatStarterExplain", "chatStarterExample", "chatStarterQuiz"];

/**
 * "Ask your tutor": a conversation with the learner's own tutor agent about
 * the lesson on screen, by text or by voice.
 *
 * The agent is found or created on first open (POST /api/tutor/agent). The
 * first message is prefixed with a compact lesson context block (see
 * lessonContext.ts) so the tutor teaches THIS lesson in the learner's
 * language; the learner only ever sees what they typed. One session per
 * lesson, shared by text and voice turns.
 *
 * If the workspace has no room for the tutor agent the server says so in
 * plain words; that message is shown here and the lesson, quiz and reviews
 * above keep working.
 */
export default function TutorChat({
  plan,
  lesson,
  moduleTitle,
}: {
  plan: TutorPlan;
  lesson: TutorLesson;
  moduleTitle?: string | null;
}) {
  const t = useTranslations("tutorPage");
  const [open, setOpen] = useState(false);
  const [agentState, setAgentState] = useState<AgentState>({ kind: "idle" });
  const [turns, setTurns] = useState<Turn[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [talkOpen, setTalkOpen] = useState(false);
  const sessionIdRef = useRef<string | null>(null);
  // false until a turn carrying the lesson context has reached the tutor.
  const contextSentRef = useRef(false);
  const controllerRef = useRef<any>(null);
  const logRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const controller = controllerRef;
    return () => {
      // Leaving the lesson mid-reply: stop the stream instead of letting it
      // run on for a screen nobody is looking at.
      try {
        controller.current?.cancel?.();
      } catch {
        // Best-effort.
      }
    };
  }, []);

  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [turns]);

  const adoptSession = useCallback((id: string | null | undefined) => {
    if (!id) return;
    sessionIdRef.current = id;
    setSessionId(id);
  }, []);

  const provision = useCallback(async () => {
    setAgentState({ kind: "loading" });
    try {
      const result: TutorAgentResult = await api.ensureTutorAgent(plan.id);
      let agent: TutorAgentRow = { id: result.agentId };
      try {
        // The call panel reads the agent's provider and model; the row is
        // best-effort (a quiet, uncached read), the id alone is enough to chat.
        const row: TutorAgentRow | null = await api.getTutorAgentRow(result.agentId);
        if (row && row.id === result.agentId) agent = row;
      } catch {
        // Keep the id-only agent.
      }
      setAgentState({ kind: "ready", agent });
    } catch (err: any) {
      setAgentState({ kind: "error", message: err?.message || "" });
    }
  }, [plan.id]);

  function openPanel() {
    setOpen(true);
    if (agentState.kind === "idle" || agentState.kind === "error") void provision();
  }

  async function send(raw: string) {
    const typed = (raw || "").trim();
    if (!typed || sending || agentState.kind !== "ready") return;
    const agentId = agentState.agent.id;
    const message = contextSentRef.current
      ? clipChatMessage(typed)
      : composeFirstMessage(
          buildLessonContext({
            topic: plan.topic,
            level: plan.level,
            language: plan.language,
            languageName: languageLabel(plan.language),
            moduleTitle,
            lessonTitle: lesson.title,
            objective: lesson.objective,
            keyPoints: lesson.content?.keyPoints,
            wrongQuestions: wrongQuestions(lesson),
          }),
          typed
        );
    setTurns((prev) => [...prev, { role: "user", text: typed }, { role: "assistant", text: "" }]);
    setInput("");
    setSending(true);
    setError(null);
    try {
      const newSessionId = await api.streamChat(
        agentId,
        message,
        sessionIdRef.current,
        (chunk: string) => {
          contextSentRef.current = true;
          setTurns((prev) => {
            const last = prev[prev.length - 1];
            if (!last || last.role !== "assistant") return prev;
            return [...prev.slice(0, -1), { ...last, text: last.text + chunk }];
          });
        },
        undefined,
        undefined,
        undefined,
        undefined,
        {
          onController: (controller) => {
            controllerRef.current = controller;
          },
        }
      );
      contextSentRef.current = true;
      adoptSession(newSessionId);
    } catch (err: any) {
      setError(err?.message || t("genericError"));
      // Drop the empty placeholder so a failed turn leaves no blank bubble.
      setTurns((prev) => {
        const last = prev[prev.length - 1];
        return last && last.role === "assistant" && !last.text ? prev.slice(0, -1) : prev;
      });
    } finally {
      controllerRef.current = null;
      setSending(false);
    }
  }

  function startTalk() {
    // A voice turn cannot carry the lesson context, so voice joins a
    // conversation the learner has already opened with one text turn. Nothing
    // is sent on their behalf: until then the panel says what to do.
    setTalkOpen(true);
  }

  if (!open) {
    return (
      <Card className="flex-row flex-wrap items-center justify-between gap-4 p-5" data-testid="tutor-chat-closed">
        <div className="min-w-0">
          <h3 className="text-sm font-semibold text-foreground">{t("askTutor")}</h3>
          <p className="mt-1 text-sm text-muted-foreground">{t("askTutorIntro")}</p>
        </div>
        <Button type="button" variant="outline" className="shrink-0" onClick={openPanel} data-testid="tutor-chat-open">
          <MessageCircle aria-hidden="true" />
          {t("askTutor")}
        </Button>
      </Card>
    );
  }

  return (
    <Card className="gap-0 p-5" data-testid="tutor-chat">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-sm font-semibold text-foreground">{t("askTutor")}</h3>
        <div className="flex flex-wrap items-center gap-2">
          {agentState.kind === "ready" && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => (talkOpen ? setTalkOpen(false) : startTalk())}
              data-testid="tutor-talk"
            >
              <Mic aria-hidden="true" />
              {talkOpen ? t("talkClose") : t("talk")}
            </Button>
          )}
          <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(false)}>
            {t("chatHide")}
          </Button>
        </div>
      </div>

      {agentState.kind === "loading" && (
        <p className="mt-3 flex items-center gap-2 text-sm text-muted-foreground" role="status">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {t("tutorPreparing")}
        </p>
      )}

      {agentState.kind === "error" && (
        <div className="mt-3">
          <Alert variant="warning">
            <AlertDescription>
              <p>{agentState.message || t("genericError")}</p>
              <p>{t("tutorUnavailableHint")}</p>
            </AlertDescription>
          </Alert>
          <div className="mt-3">
            <Button type="button" variant="outline" size="sm" onClick={() => void provision()}>
              {t("retry")}
            </Button>
          </div>
        </div>
      )}

      {agentState.kind === "ready" && (
        <>
          <div
            ref={logRef}
            role="log"
            aria-live="polite"
            aria-relevant="additions"
            className="mt-3 flex max-h-[420px] flex-col gap-3 overflow-y-auto"
          >
            {turns.length === 0 && <p className="text-sm text-muted-foreground">{t("chatEmpty")}</p>}
            {turns.map((turn, i) => {
              const uiBlock = turn.role === "assistant" && turn.text ? extractUiSpec(turn.text) : null;
              const displayText = uiBlock ? uiBlock.text : turn.text;
              const waiting = turn.role === "assistant" && !turn.text && sending && i === turns.length - 1;
              return (
                <div key={i} className={cn("flex flex-col gap-1", turn.role === "user" ? "items-end" : "items-start")}>
                  <span className="sr-only">{turn.role === "user" ? t("chatYou") : t("chatTutor")}</span>
                  {(displayText || waiting) && (
                    <div
                      className={cn(
                        "max-w-[85%] rounded-xl px-4 py-2.5 text-sm leading-relaxed [overflow-wrap:anywhere]",
                        turn.role === "user"
                          ? "whitespace-pre-wrap bg-primary text-primary-foreground"
                          : "border border-border bg-secondary/40 text-foreground"
                      )}
                    >
                      {turn.role === "user" ? turn.text : waiting ? t("chatThinking") : <RichText text={displayText} />}
                    </div>
                  )}
                  {uiBlock && (
                    <div className="max-w-[85%]">
                      <GenerativeUiRenderer
                        spec={uiBlock.spec}
                        errors={uiBlock.errors}
                        onAction={(action) => {
                          if (!sending) void send(action.message);
                        }}
                      />
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          {turns.length === 0 && (
            <div className="mt-3 flex flex-wrap gap-2">
              {STARTER_KEYS.map((key) => {
                const text = t(key);
                return (
                  <Button key={key} type="button" variant="outline" size="sm" disabled={sending} onClick={() => void send(text)}>
                    {text}
                  </Button>
                );
              })}
            </div>
          )}

          {error && (
            <Alert variant="destructive" className="mt-3">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          <form
            className="mt-3 flex items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              void send(input);
            }}
          >
            <Textarea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  void send(input);
                }
              }}
              placeholder={t("chatPlaceholder")}
              aria-label={t("chatPlaceholder")}
              rows={2}
              maxLength={4000}
              disabled={sending}
              className="min-w-0 flex-1"
            />
            <Button type="submit" disabled={sending || !input.trim()} data-testid="tutor-chat-send">
              {sending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Send aria-hidden="true" />}
              {t("chatSend")}
            </Button>
          </form>

          {talkOpen && (
            <div className="mt-4 border-t border-border pt-4" data-testid="tutor-call">
              {sessionId ? (
                <CallPanel agent={agentState.agent} sessionId={sessionId} onSessionId={adoptSession} />
              ) : sending ? (
                <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
                  <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                  {t("talkPreparing")}
                </p>
              ) : (
                <p className="text-sm text-muted-foreground">{t("talkNeedsMessage")}</p>
              )}
            </div>
          )}
        </>
      )}
    </Card>
  );
}
