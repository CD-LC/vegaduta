"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import {
  ArrowRight,
  BookOpen,
  CheckCircle2,
  ChevronDown,
  Circle,
  CircleDashed,
  CircleHelp,
  ListChecks,
  Lock,
  Sparkles,
} from "lucide-react";
import { api } from "../../../lib/api";
import { cn } from "../../../lib/utils";
import { Button } from "../../ui/button";
import { Textarea } from "../../ui/textarea";
import type { StarterTemplate } from "../starterTemplates";
import AgentStartingPoints from "./AgentStartingPoints";
import {
  DESCRIBE_FIELD_LABEL,
  DESCRIBE_MAX_CHARS,
  DESCRIBE_MIN_CHARS,
  GUIDE_ANCHORS,
  buildDescribeInstruction,
  buildFallbackDraft,
  checklistProgress,
  deriveChecklist,
  describeState,
  isPristineDraft,
  nextStep,
  normalizeSentence,
  parseConnectedChannels,
  parseDraftReply,
  type AgentDraft,
  type ConnectedChannel,
  type GuideAction,
  type GuideStep,
  type GuideStepStatus,
} from "./agentChecklist";
import { AGENT_GLOSSARY } from "./glossary";
import { goToGuideAnchor } from "./guideDom";
import { requestDraftQuietly } from "./quietDraft";
import { useTranslations } from "../../../lib/i18n";

/**
 * The persistent "Create your agent" checklist (2026-09-19).
 *
 * Founder feedback: "still users get lost in creating agents - give them
 * proper guidelines." What existed: AgentsGuide, a six-step modal that opens
 * once and closes, and TemplateSetupGuide, which only appears on the template
 * gallery. Neither is on the screen while the person is actually building, and
 * neither knows what they have done. This one sits above the workbench for
 * the whole session and is driven by the REAL draft/agent state, so it always
 * answers the two questions a lost person has: "what have I done?" and "what
 * do I click next?".
 *
 * Design decisions worth keeping:
 *   - Inline, not a modal. The product tour and AgentsGuide already fight over
 *     the overlay layer on first run (see AgentsGuide's header); a sixth
 *     overlay is the last thing a new tenant needs. It is a plain card in the
 *     page flow, collapsible to one line, and the choice is remembered.
 *   - Every button goes somewhere real. Anchors open the collapsed section and
 *     focus the field (guideDom); if the control is not on the page the card
 *     says so in words rather than doing nothing.
 *   - "Describe it in one sentence" reuses POST /api/assist/draft (the Ask-AI
 *     endpoint AskAiHelper already calls - no new backend). It NEVER
 *     auto-applies: the person sees the drafted name and instructions and
 *     presses "Use this", same review-first posture as AskAiHelper.
 *     That endpoint does NOT work for everyone: AssistService sends only
 *     plan == FREE tenants to the hosted pool, so a workspace on the
 *     complimentary signup grant with no provider key gets HTTP 400 - i.e.
 *     for many first-time users the AI draft fails every time. So the call is
 *     made quietly (quietDraft.ts: no global toast, really cancelled at
 *     DRAFT_TIMEOUT_MS) and ANY miss - 400, 429, 503, offline, timeout, empty
 *     reply - becomes a draft built locally from the sentence, with one calm
 *     line saying so. The copy never promises the AI draft, never claims "no
 *     API key needed", and never tells the person a retry will help.
 *   - Ticks: a step shows Done only for something true of THIS agent that the
 *     person did or chose. Settings that are simply on by default render
 *     "Automatic" and are left out of the progress count (agentChecklist.ts).
 *     Channel status is one guarded read of GET /api/channels/status, matched
 *     to this agent by defaultAgentId; a failed read renders "Not checked".
 *   - The starting points (template / describe / sandbox) show for a pristine
 *     draft when the agent list is empty. An empty list is NOT proof of zero
 *     agents - agents/page.tsx swallows a failed listAgents and leaves [] -
 *     so that block's heading makes no claim about how many agents exist.
 *     A caller that knows the list has not loaded can pass
 *     agentsLoaded={false} to hold the block back; agents/page.tsx does until
 *     its first listAgents settles.
 *
 * Copy is English literals here and in agentChecklist.ts/glossary.ts on
 * purpose - see glossary.ts's header.
 */

const COLLAPSED_KEY = "vd_agent_checklist_collapsed_v1";
const TESTED_PREFIX = "vd_agent_tested_v1:";
// AgentPlayground's own transcript key (module-private there:
// PLAYGROUND_STORAGE_PREFIX). Read-only here, to notice "this agent has
// replied to this person". That record expires after 24h, so the first
// sighting is copied to TESTED_PREFIX and the tick survives the expiry.
//
// The tick needs a finished ASSISTANT turn with text in it. A transcript
// holding only the person's own message (the send failed and the empty
// pending reply was dropped) is not a test, and because the tick is copied to
// a permanent key it must not be set from one. If the playground ever renames
// its key or changes the turn shape, this step stays un-ticked.
const PLAYGROUND_PREFIX = "agentic:playground:";
/** Dispatched on window by the playground after a successful send: detail {agentId}. */
const PLAYGROUND_TURN_EVENT = "vd:playground-turn";
const DRAFT_TIMEOUT_MS = 40_000;
const TESTED_POLL_MS = 4000;

function hasAssistantReply(turns: unknown): boolean {
  if (!Array.isArray(turns)) return false;
  return turns.some((turn) => {
    if (!turn || typeof turn !== "object") return false;
    const rec = turn as Record<string, unknown>;
    return rec.role === "assistant" && rec.pending !== true && typeof rec.content === "string" && rec.content.trim().length > 0;
  });
}

function markTested(agentId: string): void {
  try {
    window.localStorage.setItem(TESTED_PREFIX + agentId, "1");
  } catch {
    // Storage disabled - the tick holds for this page view only.
  }
}

function readTested(agentId: string | null): boolean {
  if (!agentId || typeof window === "undefined") return false;
  try {
    if (window.localStorage.getItem(TESTED_PREFIX + agentId)) return true;
    const raw = window.localStorage.getItem(PLAYGROUND_PREFIX + agentId);
    if (!raw) return false;
    const parsed = JSON.parse(raw);
    if (!parsed || !hasAssistantReply(parsed.turns)) return false;
    markTested(agentId);
    return true;
  } catch {
    // Private mode / malformed record - not tested as far as we can tell.
    return false;
  }
}

const STATUS_LABEL: Record<GuideStepStatus, string> = {
  done: "Done",
  todo: "To do",
  optional: "Optional",
  auto: "Automatic",
  locked: "After saving",
  unknown: "Not checked",
};

function StatusIcon({ status }: { status: GuideStepStatus }) {
  if (status === "done") return <CheckCircle2 size={18} style={{ color: "var(--state-success)" }} aria-hidden="true" />;
  if (status === "locked") return <Lock size={16} className="text-muted-foreground" aria-hidden="true" />;
  if (status === "unknown") return <CircleHelp size={18} className="text-muted-foreground" aria-hidden="true" />;
  if (status === "auto") return <Sparkles size={16} className="text-muted-foreground" aria-hidden="true" />;
  if (status === "optional") return <CircleDashed size={18} className="text-muted-foreground" aria-hidden="true" />;
  return <Circle size={18} className="text-primary" aria-hidden="true" />;
}

export interface CreateAgentChecklistProps {
  isDraft: boolean;
  agentId: string | null;
  name: string;
  systemPrompt: string;
  /** "SIMPLE" | "ADVANCED" - the draft's mode, or the saved agent's effective mode. */
  mode: string;
  knowledgeBaseEnabled: boolean;
  collectionCount: number;
  allowedCollectionCount: number;
  allowedToolCount: number;
  templateKey: string | null;
  /** Length of the agent list the page holds. 0 can also mean "failed to load". */
  totalAgents: number;
  /** false = the caller knows the list has not loaded; the starting points are held back. */
  agentsLoaded?: boolean;
  saving: boolean;
  onSave: () => void;
  /** Draft only. The workbench decides how to merge (it never overwrites a typed name). */
  onApplyDraft: (draft: AgentDraft) => void;
  onPickStarter: (starter: StarterTemplate) => void;
  /** Draft only. Fills the draft with a console-gallery template (e.g. "data-analyst"). */
  onPickTemplate?: (templateKey: string) => void;
}

export default function CreateAgentChecklist(props: CreateAgentChecklistProps) {
    const tHC = useTranslations("hcComponentsAgentsGuideCreateAgentChecklist");
  const {
    isDraft,
    agentId,
    name,
    systemPrompt,
    mode,
    knowledgeBaseEnabled,
    collectionCount,
    allowedCollectionCount,
    allowedToolCount,
    templateKey,
    totalAgents,
    agentsLoaded,
    saving,
    onSave,
    onApplyDraft,
    onPickStarter,
  } = props;

  // ---- collapsed preference ------------------------------------------------
  // null = no stored choice: open for someone with 0-1 agents, one line for
  // someone who clearly knows the screen. Read after mount (hydration-safe).
  const [collapsedPref, setCollapsedPref] = useState<boolean | null>(null);
  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(COLLAPSED_KEY);
      if (raw === "1") setCollapsedPref(true);
      else if (raw === "0") setCollapsedPref(false);
    } catch {
      // Storage disabled - the default below applies every visit.
    }
  }, []);
  const collapsed = collapsedPref ?? totalAgents > 1;
  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsedPref(next);
    try {
      window.localStorage.setItem(COLLAPSED_KEY, next ? "1" : "0");
    } catch {
      // Non-fatal: the choice just does not survive a reload.
    }
  }

  // ---- "tested" - from the playground's own saved transcript ---------------
  const [tested, setTested] = useState(false);
  useEffect(() => {
    setTested(readTested(agentId));
    if (!agentId) return undefined;
    // Primary signal: the playground announces a successful send with a
    // window CustomEvent (detail {agentId}), so the tick lands at once.
    const onTurn = (event: Event) => {
      const detail = (event as CustomEvent<{ agentId?: unknown } | null>).detail;
      if (!detail || detail.agentId !== agentId) return;
      markTested(agentId);
      setTested(true);
    };
    window.addEventListener(PLAYGROUND_TURN_EVENT, onTurn);
    // Fallback: a playground build that does not dispatch the event, or a
    // reply that landed while this card was unmounted. Same-tab localStorage
    // writes fire no "storage" event, so poll - cheap (two key reads), and it
    // stops the moment the tick lands.
    const timer = setInterval(() => {
      if (readTested(agentId)) {
        setTested(true);
        clearInterval(timer);
      }
    }, TESTED_POLL_MS);
    return () => {
      window.removeEventListener(PLAYGROUND_TURN_EVENT, onTurn);
      clearInterval(timer);
    };
  }, [agentId]);

  // ---- connected channels - one guarded read, only once an agent exists ----
  const [connectedChannels, setConnectedChannels] = useState<ConnectedChannel[] | null>(null);
  const hasSavedAgent = !isDraft && !!agentId;
  useEffect(() => {
    if (!hasSavedAgent) return undefined;
    let cancelled = false;
    Promise.resolve()
      .then(() => api.getChannelsStatus())
      .then((raw) => {
        if (!cancelled) setConnectedChannels(parseConnectedChannels(raw));
      })
      .catch(() => {
        // 403 for a non-admin, or the network: "not checked", never "none".
        if (!cancelled) setConnectedChannels(null);
      });
    return () => {
      cancelled = true;
    };
  }, [hasSavedAgent]);

  const steps = useMemo(
    () =>
      deriveChecklist({
        isDraft,
        name,
        systemPrompt,
        mode,
        knowledgeBaseEnabled,
        collectionCount,
        allowedCollectionCount,
        allowedToolCount,
        tested,
        agentId,
        connectedChannels,
      }),
    [
      isDraft,
      name,
      systemPrompt,
      mode,
      knowledgeBaseEnabled,
      collectionCount,
      allowedCollectionCount,
      allowedToolCount,
      tested,
      agentId,
      connectedChannels,
    ]
  );
  const progress = checklistProgress(steps);
  const upNext = nextStep(steps);
  const pristine = isPristineDraft({ isDraft, name, systemPrompt, templateKey });
  const showStartingPoints = pristine && totalAgents === 0 && agentsLoaded !== false;

  // ---- glossary --------------------------------------------------------------
  const [glossaryOpen, setGlossaryOpen] = useState(false);

  // ---- a button whose target is not on the page says so ----------------------
  const [anchorMiss, setAnchorMiss] = useState<string | null>(null);
  function runAction(step: GuideStep, action: GuideAction) {
    setAnchorMiss(null);
    if (action.kind === "save") {
      onSave();
      return;
    }
    if (action.kind === "anchor") {
      if (action.anchor === GUIDE_ANCHORS.describe && collapsed) toggleCollapsed();
      // The describe box only mounts once the card is open - next frame.
      const go = () => {
        if (!goToGuideAnchor(action.anchor)) {
          setAnchorMiss(`We could not find that part of the page for "${step.title}". Scroll down the left column and look for it by name.`);
        }
      };
      if (typeof window !== "undefined" && typeof window.requestAnimationFrame === "function") {
        window.requestAnimationFrame(go);
      } else {
        go();
      }
    }
  }

  // ---- describe it in one sentence -------------------------------------------
  const [sentence, setSentence] = useState("");
  const [drafting, setDrafting] = useState(false);
  const [preview, setPreview] = useState<{ draft: AgentDraft; source: "ai" | "fallback" } | null>(null);
  const [describeError, setDescribeError] = useState<string | null>(null);
  const requestRef = useRef(0);
  const mountedRef = useRef(true);
  // The in-flight draft request, so leaving the page / switching agent / a
  // second press really cancels it instead of letting it finish unheard.
  const abortRef = useRef<AbortController | null>(null);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestRef.current += 1;
      abortRef.current?.abort();
    };
  }, []);
  // Switching agent / starting a new draft abandons any in-flight drafting.
  useEffect(() => {
    requestRef.current += 1;
    abortRef.current?.abort();
    setDrafting(false);
    setPreview(null);
    setDescribeError(null);
  }, [agentId, isDraft]);

  async function handleDescribe() {
    const clean = normalizeSentence(sentence);
    if (clean.length < DESCRIBE_MIN_CHARS) {
      setDescribeError("Write a few more words first, for example: \"Answer customer questions for my bakery and take cake orders.\"");
      return;
    }
    setDescribeError(null);
    setPreview(null);
    setDrafting(true);
    const requestId = (requestRef.current += 1);
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    // requestDraftQuietly never throws, never toasts, and cancels itself at
    // the deadline. null covers every miss alike: no provider for this
    // workspace (400), quota (429), no model free (503), offline, timeout, an
    // empty reply. The person gets the locally built draft either way.
    let suggestion: string | null = null;
    try {
      suggestion = await requestDraftQuietly(
        { fieldLabel: DESCRIBE_FIELD_LABEL, instruction: buildDescribeInstruction(clean) },
        { timeoutMs: DRAFT_TIMEOUT_MS, signal: controller.signal }
      );
    } catch {
      // Belt and braces: the contract is "never throws".
      suggestion = null;
    }
    if (abortRef.current === controller) abortRef.current = null;
    if (!mountedRef.current || requestRef.current !== requestId) return;
    setPreview(
      suggestion
        ? { draft: parseDraftReply(suggestion, clean), source: "ai" }
        : { draft: buildFallbackDraft(clean), source: "fallback" }
    );
    setDrafting(false);
  }

  function handleUsePreview() {
    if (!preview) return;
    onApplyDraft(preview.draft);
    setPreview(null);
    setSentence("");
    // Land them on what was just filled in, so the change is visible.
    if (typeof window !== "undefined" && typeof window.requestAnimationFrame === "function") {
      window.requestAnimationFrame(() => goToGuideAnchor(GUIDE_ANCHORS.instructions));
    }
  }

  const d = describeState({ isDraft, name, systemPrompt });
  const showDescribeBox = isDraft && !d.done;
  const typedNameKept = isDraft && !!name.trim();
  const typedPromptReplaced = isDraft && !d.missingInstructions && !d.placeholderInstructions;

  return (
    <section
      aria-labelledby="agent-checklist-title"
      className="rounded-xl border border-border bg-card"
      data-testid="create-agent-checklist"
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3">
        <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <ListChecks size={16} aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 id="agent-checklist-title" className="m-0 text-sm font-semibold text-foreground">
            {isDraft ? "Create your agent" : "Finish setting up this agent"}
          </h2>
          <p className="m-0 text-xs text-muted-foreground">
            {progress.done} of {progress.total} done
            {upNext
              ? ` · Next: ${upNext.title}`
              : isDraft || steps.some((s) => s.status === "unknown")
                ? ""
                : " · Nothing left that needs you"}
          </p>
        </div>
        {collapsed && upNext && (
          <Button type="button" size="sm" onClick={() => runAction(upNext, upNext.action)} disabled={upNext.action.kind === "save" && saving}>
            {upNext.action.label}
            <ArrowRight size={13} aria-hidden="true" />
          </Button>
        )}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={toggleCollapsed}
          aria-expanded={!collapsed}
          aria-controls="agent-checklist-body"
        >
          {collapsed ? "Show steps" : "Hide"}
          <ChevronDown size={13} className={cn("transition-transform", !collapsed && "rotate-180")} aria-hidden="true" />
        </Button>
      </div>

      {/* A slim, honest progress bar: width is done/total, nothing animated in. */}
      <div className="mx-4 h-1 overflow-hidden rounded-full bg-secondary" aria-hidden="true">
        <div
          className="h-full rounded-full bg-primary transition-[width] duration-300"
          style={{ width: `${Math.round((progress.done / Math.max(1, progress.total)) * 100)}%` }}
        />
      </div>

      {anchorMiss && (
        <p role="status" className="mx-4 mt-3 rounded-lg border border-border bg-secondary/40 px-3 py-2 text-xs text-foreground">
          {anchorMiss}
        </p>
      )}

      {collapsed ? (
        <div className="h-3" />
      ) : (
        <div id="agent-checklist-body" className="px-4 pb-4 pt-3">
          {showStartingPoints && (
            <AgentStartingPoints
              onPickStarter={onPickStarter}
              onPickTemplate={props.onPickTemplate}
              onDescribe={() => goToGuideAnchor(GUIDE_ANCHORS.describe)}
            />
          )}

          <ol className="m-0 flex list-none flex-col gap-1 p-0">
            {steps.map((step, i) => {
              const isNext = upNext?.id === step.id;
              return (
                <li
                  key={step.id}
                  className={cn("rounded-lg border px-3 py-2.5", isNext ? "border-primary/40 bg-primary/5" : "border-transparent")}
                  aria-current={isNext ? "step" : undefined}
                >
                  <div className="flex flex-wrap items-start gap-x-3 gap-y-2">
                    <span className="mt-0.5 shrink-0">
                      <StatusIcon status={step.status} />
                    </span>
                    <div className="min-w-0 flex-1 basis-56">
                      <div className="flex flex-wrap items-center gap-x-2 gap-y-0.5">
                        <span className="text-sm font-semibold text-foreground">
                          {i + 1}. {step.title}
                        </span>
                        <span className="rounded-full border border-border px-1.5 py-px text-[0.625rem] font-medium uppercase tracking-wide text-muted-foreground">
                          {STATUS_LABEL[step.status]}
                        </span>
                      </div>
                      <p className="m-0 mt-0.5 text-xs leading-relaxed text-muted-foreground">{step.help}</p>
                      <p className="m-0 mt-1 text-xs leading-relaxed text-foreground">{step.detail}</p>
                    </div>
                    {step.action.kind === "link" ? (
                      <Button asChild variant={isNext ? "default" : "outline"} size="sm">
                        <Link href={step.action.href}>{step.action.label}</Link>
                      </Button>
                    ) : (
                      <Button
                        type="button"
                        variant={isNext || (step.id === "describe" && step.action.kind === "save") ? "default" : "outline"}
                        size="sm"
                        disabled={step.action.kind === "save" && (saving || (step.id !== "describe" && !d.done))}
                        title={
                          step.action.kind === "save" && step.id !== "describe" && !d.done
                            ? "Finish step 1 first: the agent needs a name and instructions."
                            : undefined
                        }
                        onClick={() => runAction(step, step.action)}
                      >
                        {step.action.kind === "save" && saving ? "Saving…" : step.action.label}
                      </Button>
                    )}
                  </div>

                  {step.id === "describe" && showDescribeBox && (
                    <div id={GUIDE_ANCHORS.describe} className="mt-3 rounded-lg border border-border bg-background p-3">
                      <label htmlFor="agent-guide-describe-input" className="flex items-center gap-1.5 text-xs font-semibold text-foreground">
                        <Sparkles size={13} className="text-primary" aria-hidden="true" />
                        {tHC("s8")}
                      </label>
                      <Textarea
                        id="agent-guide-describe-input"
                        className="mt-2 min-h-16"
                        value={sentence}
                        maxLength={DESCRIBE_MAX_CHARS}
                        disabled={drafting}
                        placeholder={tHC("s7")}
                        onChange={(e) => {
                          setSentence(e.target.value);
                          if (describeError) setDescribeError(null);
                        }}
                      />
                      {describeError && (
                        <p role="alert" className="m-0 mt-2 text-xs" style={{ color: "var(--state-error)" }}>
                          {describeError}
                        </p>
                      )}
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        <Button type="button" size="sm" onClick={handleDescribe} disabled={drafting}>
                          {drafting ? "Drafting…" : preview ? "Draft again" : "Draft my agent"}
                        </Button>
                        <span className="text-[0.6875rem] text-muted-foreground">
                          {drafting
                            ? "Working on it. You will get a draft either way."
                            : "Nothing is changed until you press \"Use this\". You can edit everything afterwards."}
                        </span>
                      </div>

                      {preview && (
                        <div className="mt-3 rounded-lg border border-border bg-card p-3" role="status">
                          {preview.source === "fallback" && (
                            <p className="m-0 mb-2 text-xs text-muted-foreground" data-testid="basic-draft-note">
                              {tHC("s6")}
                            </p>
                          )}
                          <div className="text-[0.6875rem] font-semibold uppercase tracking-wide text-muted-foreground">Name</div>
                          <div className="text-sm font-medium text-foreground">{preview.draft.name}</div>
                          {typedNameKept && (
                            <p className="m-0 mt-0.5 text-[0.6875rem] text-muted-foreground">
                              {tHC("s5")}{name.trim()}{tHC("s4")}
                            </p>
                          )}
                          <div className="mt-2 text-[0.6875rem] font-semibold uppercase tracking-wide text-muted-foreground">
                            Instructions
                          </div>
                          <div className="max-h-44 overflow-y-auto whitespace-pre-wrap text-xs leading-relaxed text-foreground">
                            {preview.draft.systemPrompt}
                          </div>
                          {typedPromptReplaced && (
                            <p className="m-0 mt-1 text-[0.6875rem] text-muted-foreground">
                              {tHC("s3")}
                            </p>
                          )}
                          <div className="mt-3 flex flex-wrap gap-2">
                            <Button type="button" size="sm" onClick={handleUsePreview}>
                              {tHC("s2")}
                            </Button>
                            <Button type="button" variant="outline" size="sm" onClick={() => setPreview(null)}>
                              Discard
                            </Button>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
                </li>
              );
            })}
          </ol>

          <div className="mt-3 border-t border-border pt-3">
            <button
              type="button"
              onClick={() => setGlossaryOpen((v) => !v)}
              aria-expanded={glossaryOpen}
              aria-controls="agent-checklist-glossary"
              className="flex items-center gap-1.5 rounded-md border-0 bg-transparent p-0 text-xs font-medium text-primary shadow-none underline-offset-4 hover:underline"
            >
              <BookOpen size={13} aria-hidden="true" />
              {glossaryOpen ? "Hide the word list" : "What do the words on this page mean?"}
            </button>
            {glossaryOpen && (
              <dl id="agent-checklist-glossary" className="m-0 mt-3 grid grid-cols-1 gap-x-6 gap-y-3 md:grid-cols-2">
                {AGENT_GLOSSARY.map((g) => (
                  <div key={g.key} className="min-w-0">
                    <dt className="text-xs font-semibold text-foreground">
                      {g.term}
                      {g.onScreen.toLowerCase() !== g.term.toLowerCase() && (
                        <span className="ml-1.5 font-normal text-muted-foreground">{tHC("s1")}{g.onScreen}&quot;</span>
                      )}
                    </dt>
                    <dd className="m-0 mt-0.5 text-xs leading-relaxed text-muted-foreground">
                      {g.meaning} <span className="text-foreground/80">Example: {g.example}</span>
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
