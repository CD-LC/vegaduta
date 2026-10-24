"use client";

import { useEffect, useRef, useState } from "react";
import { Play, Square, Loader2, MessageSquareOff, Sparkles, Lightbulb } from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Textarea } from "../ui/textarea";
import TraceView from "./TraceView";
import { runMission, studio, type StudioAgent, type StudioEvent } from "./studioClient";
import { MISSION_PRESETS, toolLabel } from "./studioContent";
import { TONES } from "./studioTheme";

// "Run a mission": one real agent turn, with the trace beside the answer.
// The "same mission, no hands" comparison is the page's single strongest
// demonstration - it flips the agent's tool allow-list off, runs the same
// mission, restores the tools, and shows both answers side by side. Two
// real runs, two real messages of allowance, no trickery.

export type MissionOutcome = {
  id: string;
  mission: string;
  toolsOff: boolean;
  events: StudioEvent[];
  answer: string;
  running: boolean;
  toolCalls: number;
  ms: number | null;
};

type Props = {
  token: string;
  agent: StudioAgent;
  readOnly: boolean;
  outOfBudget: boolean;
  onBudget: (used: number, limit: number) => void;
  onAgentChanged: (agent: StudioAgent) => void;
  onFirstRun?: () => void;
  /** Missions tailored to the visitor's own topic (from the launcher). Falls back to role-based samples. */
  suggested?: string[];
  /** Prefilled into the box when this agent is first opened. */
  prefill?: string;
  /** Wall-clock cap on one run, from the server, shown as a countdown while it works. */
  timeoutSeconds?: number;
};

function sampleMissionsFor(agent: StudioAgent): string[] {
  const preset = MISSION_PRESETS.find((p) => p.name === agent.name);
  if (preset) return preset.sampleMissions;
  if (agent.tools.includes("web_search")) return MISSION_PRESETS[0].sampleMissions;
  if (agent.tools.includes("http_request")) return MISSION_PRESETS[4].sampleMissions;
  return [
    "Draft a polite reply to a customer asking whether we deliver on Sundays.",
    "Summarise what you can and cannot do in three lines.",
    "Write a two-line product description for a handmade clay diya set.",
  ];
}

export default function MissionConsole({
  token,
  agent,
  readOnly,
  outOfBudget,
  onBudget,
  onAgentChanged,
  onFirstRun,
  suggested,
  prefill,
  timeoutSeconds,
}: Props) {
  const [elapsed, setElapsed] = useState(0);
  const [mission, setMission] = useState("");
  const [current, setCurrent] = useState<MissionOutcome | null>(null);
  const [compare, setCompare] = useState<MissionOutcome | null>(null);
  const [comparing, setComparing] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const chatSessionRef = useRef<string | null>(null);
  const firstRunRef = useRef(false);

  useEffect(() => {
    // A different agent is a different conversation - and a different set
    // of sample missions. Keep the typed mission (visitors often want to
    // try the same job on two agents), drop the outcomes.
    setCurrent(null);
    setCompare(null);
    chatSessionRef.current = null;
    setMission((m) => (m.trim() ? m : (prefill ?? "")));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const run = async (
    text: string,
    toolsOff: boolean,
    setOutcome: (o: MissionOutcome) => void
  ): Promise<MissionOutcome> => {
    const outcome: MissionOutcome = {
      id: `${Date.now()}-${toolsOff ? "b" : "a"}`,
      mission: text,
      toolsOff,
      events: [],
      answer: "",
      running: true,
      toolCalls: 0,
      ms: null,
    };
    setOutcome({ ...outcome });
    const controller = new AbortController();
    abortRef.current = controller;
    await runMission(
      token,
      agent.id,
      text,
      chatSessionRef.current,
      (event) => {
        if (event.type === "budget") {
          onBudget(event.used, event.limit);
          if (event.sessionId) chatSessionRef.current = event.sessionId;
          return;
        }
        if (event.type === "token") {
          outcome.answer += event.text;
        } else {
          outcome.events = [...outcome.events, event];
          if (event.type === "tool_call") outcome.toolCalls += 1;
          if (event.type === "run_finished") outcome.ms = event.ms;
        }
        setOutcome({ ...outcome });
      },
      controller.signal
    );
    outcome.running = false;
    setOutcome({ ...outcome });
    return outcome;
  };

  const start = async () => {
    const text = mission.trim();
    if (!text || current?.running || comparing) return;
    if (!firstRunRef.current) {
      firstRunRef.current = true;
      onFirstRun?.();
    }
    setCompare(null);
    await run(text, agent.toolsOff, (o) => setCurrent(o));
  };

  const stop = () => abortRef.current?.abort();

  const runWithoutHands = async () => {
    if (!current || current.running || comparing || agent.toolsOff) return;
    setComparing(true);
    const original = agent.tools;
    const off = await studio.updateAgent(token, agent.id, { tools: [] });
    if (!off.ok) {
      setComparing(false);
      setCompare({
        id: "cmp-fail",
        mission: current.mission,
        toolsOff: true,
        events: [{ type: "error", message: off.error.message }],
        answer: "",
        running: false,
        toolCalls: 0,
        ms: null,
      });
      return;
    }
    try {
      await run(current.mission, true, (o) => setCompare(o));
    } finally {
      const restored = await studio.updateAgent(token, agent.id, { tools: original });
      if (restored.ok) onAgentChanged(restored.value);
      setComparing(false);
    }
  };

  const busy = !!current?.running || comparing;

  useEffect(() => {
    if (!busy) {
      setElapsed(0);
      return undefined;
    }
    const started = Date.now();
    const id = window.setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 500);
    return () => window.clearInterval(id);
  }, [busy]);
  const samples = suggested && suggested.length > 0 ? suggested : sampleMissionsFor(agent);

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className="font-medium">{agent.name}</span>
        <Badge variant="outline" className="text-[11px]">
          {agent.reasoningDepth.toLowerCase()} thinking
        </Badge>
        {agent.toolsOff ? (
          <Badge variant="outline" className="text-[11px]">
            <MessageSquareOff aria-hidden="true" className="mr-1 h-3 w-3 shrink-0" /> chatbot mode
          </Badge>
        ) : (
          agent.tools.map((t) => (
            <Badge key={t} variant="outline" className="text-[11px]">
              {toolLabel(t)}
            </Badge>
          ))
        )}
      </div>

      <div data-tour="mission-box">
        <Textarea
          value={mission}
          rows={3}
          maxLength={2000}
          disabled={readOnly || outOfBudget}
          placeholder="Give it a job. Be specific - a real task, not a greeting."
          onChange={(e) => setMission(e.target.value)}
          onKeyDown={(e) => {
            if ((e.metaKey || e.ctrlKey) && e.key === "Enter") void start();
          }}
        />
        <div className="mt-2 flex flex-wrap items-center gap-2">
          {!busy ? (
            <Button className={TONES.emerald.cta} onClick={start} disabled={!mission.trim() || readOnly || outOfBudget}>
              <Play aria-hidden="true" className="mr-2 h-4 w-4 shrink-0" /> Run mission
            </Button>
          ) : (
            <Button variant="outline" onClick={stop}>
              <Square aria-hidden="true" className="mr-2 h-4 w-4 shrink-0" /> Stop
            </Button>
          )}
          {current && !current.running && !agent.toolsOff && !comparing && !readOnly && !outOfBudget && (
            <Button variant="outline" onClick={runWithoutHands}>
              <MessageSquareOff aria-hidden="true" className="mr-2 h-4 w-4 shrink-0" /> Same mission, no hands
            </Button>
          )}
          {comparing && (
            <span className="inline-flex items-center gap-2 text-xs text-muted-foreground">
              <Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin shrink-0" /> Tools switched off, running
              again…
            </span>
          )}
          {busy && timeoutSeconds ? (
            <span
              className="ml-auto flex items-center gap-2 text-xs text-muted-foreground"
              role="timer"
              aria-label="Time used of the run cap"
            >
              <span className="h-1.5 w-24 overflow-hidden rounded-full bg-muted">
                <span
                  className="block h-full rounded-full bg-primary transition-all"
                  style={{ width: `${Math.min(100, (elapsed / timeoutSeconds) * 100)}%` }}
                />
              </span>
              {elapsed}s / {timeoutSeconds}s cap
            </span>
          ) : (
            <span className="ml-auto text-xs text-muted-foreground">Ctrl/⌘ + Enter to run</span>
          )}
        </div>
        {outOfBudget && (
          <p className="mt-2 text-sm text-muted-foreground">Runs are paused for this sandbox right now.</p>
        )}
      </div>

      {!current && (
        <div>
          <div className="mb-2 flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <Lightbulb aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> Try one of these
          </div>
          <div className="flex flex-wrap gap-2">
            {samples.map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => setMission(s)}
                className="rounded-full border border-border/60 bg-background px-3 py-1.5 text-left text-xs transition-colors hover:border-primary/60"
              >
                {s}
              </button>
            ))}
          </div>
        </div>
      )}

      {current && (
        <div className={`grid gap-4 ${compare ? "lg:grid-cols-2" : ""}`}>
          <OutcomeCard outcome={current} title={agent.toolsOff ? "Chatbot mode" : "With hands"} accent />
          {compare && <OutcomeCard outcome={compare} title="Same mission, no hands" />}
        </div>
      )}

      {current && compare && !current.running && !compare.running && (
        <div className="rounded-xl border border-primary/30 bg-primary/5 p-4 text-sm">
          <div className="mb-1 flex items-center gap-2 font-medium">
            <Sparkles aria-hidden="true" className="h-4 w-4 shrink-0" /> That is the whole difference.
          </div>
          <p className="text-muted-foreground">
            Same model, same mission, same instructions. Left: {current.toolCalls} real tool call
            {current.toolCalls === 1 ? "" : "s"} - it went and checked. Right: none - it could only recall and guess. An
            agent is a model with hands, a plan, and rules about when to use them.
          </p>
        </div>
      )}
    </div>
  );
}

function OutcomeCard({ outcome, title, accent }: { outcome: MissionOutcome; title: string; accent?: boolean }) {
  return (
    <div className={`rounded-xl border p-4 ${accent ? "border-primary/40" : "border-border/60"}`}>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium">{title}</span>
        {outcome.running && (
          <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
            <Loader2 aria-hidden="true" className="h-3 w-3 animate-spin shrink-0" /> working
          </span>
        )}
        {!outcome.running && (
          <span className="ml-auto text-xs text-muted-foreground">
            {outcome.toolCalls} tool call{outcome.toolCalls === 1 ? "" : "s"}
            {outcome.ms != null ? ` · ${(outcome.ms / 1000).toFixed(1)} s` : ""}
          </span>
        )}
      </div>
      <div className="grid gap-4 md:grid-cols-5">
        <div className="md:col-span-2">
          <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">What it did</div>
          <TraceView
            events={outcome.events}
            running={outcome.running}
            emptyHint={outcome.toolsOff ? "No tools, so nothing to show - it answered from memory alone." : undefined}
          />
        </div>
        <div className="md:col-span-3">
          <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">Answer</div>
          {outcome.answer ? (
            <div className="whitespace-pre-wrap break-all rounded-lg bg-muted/40 p-3 text-sm leading-relaxed">
              {outcome.answer}
            </div>
          ) : outcome.running ? (
            <div className="rounded-lg bg-muted/40 p-3 text-sm text-muted-foreground animate-pulse">Composing…</div>
          ) : (
            <div className="rounded-lg bg-muted/40 p-3 text-sm text-muted-foreground">
              No answer came back this time.
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
