"use client";

import { useCallback, useEffect, useReducer, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, Check, Loader2 } from "lucide-react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import ChatPanel from "../components/ChatPanel";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { templateNameKey, templateTaglineKey } from "../components/agentTemplates";
import {
  describeCapabilityOutcome,
  fileAnalysisUnavailableNote,
  templateWantsFileAnalysis,
} from "../components/agents/templateCapabilities";
import { tutorAvailable } from "../learn/tutor/tutorAvailability";
import {
  MISSING_NOTE_KEY,
  SETUP_LINK_KEY,
  UNCHECKED_NOTE_KEY,
  goalTitleKey,
  openKey,
  promptKey,
  routeBodyKey,
} from "./copy";
import { createStartAgent, pickProviderName } from "./createAgent";
import { flushOnboardingEvents, ONBOARDING_STEPS, type OnboardingStep } from "./events";
import FirstRunCelebration from "./FirstRunCelebration";
import { flowReducer, initialFlow, isFirstRunDone, isResumable, loadFlow, saveFlow } from "./flow";
import GoalCard from "./GoalCard";
import { GOALS, findGoal, type GoalId } from "./goals";
import { MISSING_SETUP_HREF, UNKNOWN_SIGNALS, evaluateGoal, type WorkspaceSignals } from "./prereq";
import { probeE2bCredential, probeMcpServers } from "./probes";
import { loadWorkspaceSignals } from "./signals";
import { sendOnboardingEvent } from "./telemetry";

type StartAgent = { id: string; name?: string; [key: string]: unknown };

/**
 * The /start guided flow: choose a goal, create the agent (or open the page)
 * in one click, try it with a ready prompt. Skippable at every step, resumable
 * (state in localStorage, every access guarded), and never auto-sending: the
 * sample question is placed in the playground box and the person presses Send.
 *
 * State and telemetry live in flow.ts / events.ts (pure, unit-tested). This
 * component only wires clicks to actions, loads the workspace signals the
 * cards need to be honest (signals.ts), and flushes counted events.
 */
export default function StartFlow() {
  const t = useTranslations("startFlow");
  const tTemplates = useTranslations("agentTemplates");
  const router = useRouter();

  const [state, dispatch] = useReducer(flowReducer, undefined, initialFlow);
  const [hydrated, setHydrated] = useState(false);
  const [resumed, setResumed] = useState(false);
  const [signals, setSignals] = useState<WorkspaceSignals>(UNKNOWN_SIGNALS);
  const [agent, setAgent] = useState<StartAgent | null>(null);
  const [notes, setNotes] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [agentGone, setAgentGone] = useState(false);

  // Event names already beaconed this page life (events.ts flushOnboardingEvents).
  const sentRef = useRef<Set<string>>(new Set());
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const prevStepRef = useRef<OnboardingStep | null>(null);

  // ---- load saved state once, then count the visit -----------------------
  useEffect(() => {
    const now = Date.now();
    const loaded = loadFlow(now);
    // Everything already counted in a previous visit is already sent.
    sentRef.current = new Set(loaded.fired);
    setResumed(isResumable(loaded));
    dispatch({ type: "hydrate", state: loaded });
    dispatch({ type: "visit", now });
    setHydrated(true);
  }, []);

  // ---- persist, then flush newly counted events --------------------------
  useEffect(() => {
    if (!hydrated) return;
    saveFlow(state);
    flushOnboardingEvents(state.fired, sentRef.current, sendOnboardingEvent);
  }, [hydrated, state]);

  // ---- what the workspace has (read-only, quiet) --------------------------
  useEffect(() => {
    let cancelled = false;
    loadWorkspaceSignals({
      getTenantSettings: () => api.getTenantSettings(),
      getProviderAvailability: () => api.getProviderAvailability(),
      tutorAvailable,
      getResearchStatus: () => api.getResearchStatus(),
      listMcpServers: probeMcpServers,
      getE2bCredential: probeE2bCredential,
    })
      .then((next) => {
        if (!cancelled) setSignals(next);
      })
      .catch(() => {
        // allSettled inside makes this unreachable in practice; "could not
        // check" is still the honest fallback, and it ends the checking state.
        if (!cancelled) setSignals({ ...UNKNOWN_SIGNALS, loaded: true });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // ---- resume into the try step: find the agent again ---------------------
  useEffect(() => {
    if (!hydrated || state.step !== "try" || !state.agentId || agent?.id === state.agentId) return undefined;
    let cancelled = false;
    void api
      .listAgents()
      .then((list: unknown) => {
        if (cancelled) return;
        const found = Array.isArray(list)
          ? (list.find((a: any) => a?.id === state.agentId) as StartAgent | undefined)
          : undefined;
        if (found) {
          setAgent(found);
        } else {
          setAgentGone(true);
          dispatch({ type: "agent-missing" });
        }
      })
      .catch(() => {
        /* leave the "opening your agent" state; Start over is right there */
      });
    return () => {
      cancelled = true;
    };
  }, [hydrated, state.step, state.agentId, agent?.id]);

  // ---- the sample question is in the box: count it once -------------------
  useEffect(() => {
    if (hydrated && state.step === "try" && agent && agent.id === state.agentId && !state.prefilled) {
      dispatch({ type: "prefilled" });
    }
  }, [hydrated, state.step, state.agentId, state.prefilled, agent]);

  // ---- move keyboard focus to the new step's heading ----------------------
  useEffect(() => {
    if (!hydrated) return;
    if (prevStepRef.current !== null && prevStepRef.current !== state.step) headingRef.current?.focus();
    prevStepRef.current = state.step;
  }, [hydrated, state.step]);

  const handleFirstRun = useCallback(() => {
    dispatch({ type: "first-run", now: Date.now() });
  }, []);

  function handleChoose(id: GoalId) {
    const goal = findGoal(id);
    if (!goal) return;
    const card = evaluateGoal(goal, signals);
    setCreateError(null);
    setAgentGone(false);
    dispatch({
      type: "select-goal",
      goal: id,
      templateKey: card.action.type === "agent" ? card.action.templateKey : null,
    });
  }

  function handleSkip(step: OnboardingStep) {
    // Counted and saved synchronously: the navigation below can unmount this
    // component before the persist/flush effect would have run.
    const action = { type: "skip", step } as const;
    const next = flowReducer(state, action);
    saveFlow(next);
    flushOnboardingEvents(next.fired, sentRef.current, sendOnboardingEvent);
    dispatch(action);
    router.push("/home");
  }

  async function handleCreate(templateKey: string) {
    if (busy) return;
    setBusy(true);
    setCreateError(null);
    try {
      let providers: unknown = [];
      try {
        providers = await api.listProviders();
      } catch {
        /* the default provider name is what the workbench falls back to too */
      }
      const created = await createStartAgent(
        templateKey,
        { name: tTemplates(templateNameKey(templateKey)), providerName: pickProviderName(providers) },
        api
      );
      // Re-read the agent: the playground wants the full record (modalities
      // etc.), and the file-analysis note needs it AFTER capabilities are on.
      let full: StartAgent | null = null;
      try {
        const list = await api.listAgents();
        const hit = Array.isArray(list) ? list.find((a) => a?.id === created.id) : undefined;
        full = hit ? { ...hit } : null;
      } catch {
        /* fall back to the create response */
      }
      const fileNote = templateWantsFileAnalysis(templateKey)
        ? fileAnalysisUnavailableNote(full as any, tTemplates)
        : null;
      setNotes(
        [describeCapabilityOutcome(created.capabilityOutcome, tTemplates), fileNote].filter((n): n is string =>
          Boolean(n)
        )
      );
      setAgent(full ?? { id: created.id, name: created.name });
      dispatch({ type: "agent-created", agentId: created.id, templateKey });
    } catch (err: any) {
      setCreateError(err?.message || t("createFailed"));
    } finally {
      setBusy(false);
    }
  }

  function handleStartOver() {
    setAgent(null);
    setNotes([]);
    setCreateError(null);
    setAgentGone(false);
    dispatch({ type: "restart", now: Date.now() });
  }

  if (!hydrated) {
    return <div className="min-h-[14rem]" aria-busy="true" data-testid="start-flow-loading" />;
  }

  const stepIndex = ONBOARDING_STEPS.indexOf(state.step);
  const stepLabels: Record<OnboardingStep, string> = {
    goal: t("stepGoal"),
    create: t("stepCreate"),
    try: t("stepTry"),
  };
  const goal = findGoal(state.goal);
  const card = goal ? evaluateGoal(goal, signals) : null;
  const agentKey = card && card.action.type === "agent" ? card.action.templateKey : null;

  const skipButton = (step: OnboardingStep) => (
    <Button type="button" variant="ghost" size="sm" onClick={() => handleSkip(step)} disabled={busy}>
      {t("skipForNow")}
    </Button>
  );

  return (
    <div className="mx-auto w-full max-w-5xl space-y-6" data-testid="start-flow" data-step={state.step}>
      <nav aria-label={t("stepperLabel")}>
        <ol className="m-0 flex list-none flex-wrap items-center gap-x-4 gap-y-2 p-0">
          {ONBOARDING_STEPS.map((step, i) => {
            const done = i < stepIndex;
            const current = i === stepIndex;
            return (
              <li
                key={step}
                aria-current={current ? "step" : undefined}
                className={cn(
                  "flex items-center gap-2 text-sm",
                  current ? "font-semibold text-foreground" : "text-muted-foreground"
                )}
              >
                <span
                  className={cn(
                    "flex size-6 shrink-0 items-center justify-center rounded-full border text-xs tabular-nums",
                    current && "border-primary bg-primary text-primary-foreground",
                    done && "border-primary/50 bg-primary/10 text-primary",
                    !current && !done && "border-border"
                  )}
                  aria-hidden="true"
                >
                  {done ? <Check aria-hidden="true" className="size-3.5" /> : i + 1}
                </span>
                {stepLabels[step]}
              </li>
            );
          })}
        </ol>
      </nav>
      <p className="sr-only" role="status" aria-live="polite">
        {t("stepAnnounce", { n: stepIndex + 1, label: stepLabels[state.step] })}
      </p>

      {resumed && state.step !== "goal" && (
        <p className="m-0 text-sm text-muted-foreground" data-testid="start-resumed">
          {t("resumedNote")}
        </p>
      )}

      {/* ------------------------------------------------ step 1: goal */}
      {state.step === "goal" && (
        <section aria-labelledby="start-step-heading" className="space-y-4">
          <div>
            <h2
              id="start-step-heading"
              ref={headingRef}
              tabIndex={-1}
              className="text-xl font-semibold tracking-tight text-foreground outline-none"
            >
              {t("goalHeading")}
            </h2>
            <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{t("goalHint")}</p>
          </div>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,17rem),1fr))] gap-4">
            {GOALS.map((g) => (
              <GoalCard key={g.id} goal={g} state={evaluateGoal(g, signals)} onChoose={handleChoose} t={t} />
            ))}
          </div>
          <div>{skipButton("goal")}</div>
        </section>
      )}

      {/* ---------------------------------------------- step 2: set it up */}
      {state.step === "create" && goal && card && (
        <section aria-labelledby="start-step-heading" className="space-y-4">
          <Card className="gap-4 p-5 sm:p-6">
            <div>
              <h2
                id="start-step-heading"
                ref={headingRef}
                tabIndex={-1}
                className="text-xl font-semibold tracking-tight text-foreground outline-none"
              >
                {card.action.type === "agent"
                  ? t("createHeading", { name: tTemplates(templateNameKey(card.action.templateKey)) })
                  : t(goalTitleKey(goal.id))}
              </h2>
              <p className="mt-2 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
                {card.action.type === "agent"
                  ? t("createBody", { name: tTemplates(templateNameKey(card.action.templateKey)) })
                  : t(routeBodyKey(goal.id))}
              </p>
              {card.action.type === "agent" && (
                <p className="mt-2 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
                  {tTemplates(templateTaglineKey(card.action.templateKey))}
                </p>
              )}
            </div>

            {agentGone && <p className="m-0 text-sm text-muted-foreground">{t("agentGone")}</p>}
            {card.fellBack && <p className="m-0 text-sm text-muted-foreground">{t("noteTutorOff")}</p>}
            {card.kind === "locked" && <p className="m-0 text-sm text-muted-foreground">{t("lockedNote")}</p>}

            {(card.missing.length > 0 || card.unchecked.length > 0) && (
              <div data-testid="start-prereqs">
                <h3 className="text-sm font-semibold text-foreground">{t("beforeContinue")}</h3>
                <ul className="mt-2 list-none space-y-2 p-0 text-sm leading-relaxed text-muted-foreground">
                  {card.missing.map((item) => (
                    <li key={`m-${item}`} className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                      <span>{t(MISSING_NOTE_KEY[item])}</span>
                      <Link
                        href={MISSING_SETUP_HREF[item]}
                        className="font-medium text-primary underline-offset-4 hover:underline"
                      >
                        {t(SETUP_LINK_KEY[item])}
                      </Link>
                    </li>
                  ))}
                  {card.unchecked.map((item) => (
                    <li key={`u-${item}`}>{t(UNCHECKED_NOTE_KEY[item])}</li>
                  ))}
                </ul>
              </div>
            )}

            {createError && (
              <p role="alert" className="m-0 text-sm text-destructive">
                {createError}
              </p>
            )}

            <div className="flex flex-wrap items-center gap-3">
              {card.kind === "blocked" ? (
                card.missing.includes("provider") && (
                  <Button asChild>
                    <Link href={MISSING_SETUP_HREF.provider}>{t(SETUP_LINK_KEY.provider)}</Link>
                  </Button>
                )
              ) : card.kind === "locked" ? (
                <Button asChild>
                  <Link href="/features">{t("seePlans")}</Link>
                </Button>
              ) : card.action.type === "agent" ? (
                <Button type="button" onClick={() => void handleCreate(agentKey)} disabled={busy || !agentKey}>
                  {busy ? (
                    <>
                      <Loader2 className="motion-safe:animate-spin" aria-hidden="true" />
                      {t("creating")}
                    </>
                  ) : card.kind === "reduced" ? (
                    t("createButtonReduced")
                  ) : (
                    t("createButton")
                  )}
                </Button>
              ) : card.action.type === "route" ? (
                <Button asChild>
                  <Link href={card.action.href}>{t(openKey(goal.id))}</Link>
                </Button>
              ) : null}
              <Button type="button" variant="outline" onClick={() => dispatch({ type: "back" })} disabled={busy}>
                <ArrowLeft aria-hidden="true" />
                {t("back")}
              </Button>
              {skipButton("create")}
            </div>
          </Card>
        </section>
      )}

      {/* ------------------------------------------------- step 3: try it */}
      {state.step === "try" && (
        <section aria-labelledby="start-step-heading" className="space-y-4">
          <div>
            <h2
              id="start-step-heading"
              ref={headingRef}
              tabIndex={-1}
              className="text-xl font-semibold tracking-tight text-foreground outline-none"
            >
              {t("tryHeading")}
            </h2>
            {agent && agent.id === state.agentId ? (
              <>
                <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                  {t("agentReady", { name: String(agent.name ?? "") })} {t("tryIntro")}
                </p>
                {notes.map((note) => (
                  <p
                    key={note}
                    className="mt-1 text-sm leading-relaxed text-muted-foreground"
                    data-testid="start-capability-note"
                  >
                    {note}
                  </p>
                ))}
              </>
            ) : (
              <p className="mt-1 flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="size-4 motion-safe:animate-spin" aria-hidden="true" />
                {t("openingAgent")}
              </p>
            )}
          </div>

          {isFirstRunDone(state) && state.agentId && (
            <FirstRunCelebration
              agentId={state.agentId}
              seconds={state.firstRunSeconds}
              onAnother={handleStartOver}
              t={t}
            />
          )}

          {agent && agent.id === state.agentId && (
            <ChatPanel
              agent={agent}
              initialInput={goal ? t(promptKey(goal.id)) : undefined}
              onTurnComplete={handleFirstRun}
            />
          )}

          <div className="flex flex-wrap items-center gap-3">
            {agent && agent.id === state.agentId && (
              <Button asChild variant="outline" size="sm">
                <Link href={`/playground?agent=${encodeURIComponent(agent.id)}`}>{t("openPlayground")}</Link>
              </Button>
            )}
            <Button type="button" variant="ghost" size="sm" onClick={handleStartOver}>
              {t("startOver")}
            </Button>
            {!isFirstRunDone(state) && skipButton("try")}
          </div>
        </section>
      )}
    </div>
  );
}
