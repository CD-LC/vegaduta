"use client";

import { useEffect, useMemo, useState } from "react";
import { motion, AnimatePresence } from "motion/react";
import {
  Sparkles,
  PenLine,
  Loader2,
  Hand,
  Brain,
  FileText,
  Plug,
  ShieldCheck,
  ArrowRight,
  ArrowLeft,
  Globe,
  Code2,
  Webhook,
  Target,
  Users,
  Ban,
  SlidersHorizontal,
  Check,
  Lightbulb,
} from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Switch } from "../ui/switch";
import {
  EMPTY_BRIEF,
  JOB_IDEAS,
  JOB_PLACEHOLDERS,
  briefReady,
  draftFromBrief,
  suggestCare,
  suggestNeeds,
  type Brief,
  type Care,
  type Draft,
} from "./topicDraft";
import { toolLabel } from "./studioContent";
import WhyAgentic from "./WhyAgentic";
import { TONES, pick } from "./studioTheme";
import { useTranslations } from "../../lib/i18n";

// The briefing. Nothing is pre-built: the studio asks five short questions and
// composes the agent from the answers. Each question says WHY it is asked,
// because that is the lesson - an agent is steered by a handful of dials, and
// the person who turns them decides how precise it is.
//
// It is the first thing a visitor sees after opening or restarting a sandbox,
// and it carries no topic of its own. Placeholders hint at the shape of a good
// answer, and the idea chips only pre-fill the text box for a visitor with
// nothing in mind - neither ever becomes an agent by itself.

type StepId = "job" | "who" | "never" | "needs" | "precision";

const STEPS: { id: StepId; label: string; icon: typeof Target }[] = [
  { id: "job", label: "The job", icon: Target },
  { id: "who", label: "Who it serves", icon: Users },
  { id: "never", label: "Never do", icon: Ban },
  { id: "needs", label: "What it needs", icon: Hand },
  { id: "precision", label: "Precision", icon: SlidersHorizontal },
];

const CARE: { id: Care; label: string; blurb: string }[] = [
  { id: "FAST", label: "Fast", blurb: "One pass. Cheapest. Fine for drafts and chat." },
  { id: "CAREFUL", label: "Careful", blurb: "Checks its own answer once before replying." },
  { id: "RIGOROUS", label: "Rigorous", blurb: "Plans, works, reviews. Slower - use it when being wrong is expensive." },
];

type Props = {
  onBuild: (draft: Draft) => Promise<void>;
  onBlank: () => void;
  busy: boolean;
  readOnly: boolean;
  /** True right after a restart - only changes the greeting. */
  fresh?: boolean;
  /** Words to drop into the job box (from the hero box or a "Build this one" card). `n` changes on every new seed so the same text can be applied twice. */
  seed?: { text: string; n: number } | null;
};

export default function Genesis({ onBuild, onBlank, busy, readOnly, fresh, seed }: Props) {
  const tHC = useTranslations("hcComponentsStudioGenesis");
  const [brief, setBrief] = useState<Brief>(EMPTY_BRIEF);
  const [step, setStep] = useState<StepId>("job");
  const [needsTouched, setNeedsTouched] = useState(false);
  const [careTouched, setCareTouched] = useState(false);
  const [ph, setPh] = useState(0);

  // A seed only FILLS the job box - the visitor still reads, edits and presses Build themselves.
  useEffect(() => {
    if (!seed || !seed.text) return;
    setBrief((b) => ({ ...b, job: seed.text.slice(0, 240) }));
    setStep("job");
  }, [seed]);

  useEffect(() => {
    const id = window.setInterval(() => setPh((n) => (n + 1) % JOB_PLACEHOLDERS.length), 3200);
    return () => window.clearInterval(id);
  }, []);

  // The words of the job PRE-TICK the needs and the care level - a suggestion the visitor can overrule, never a decision made for them.
  useEffect(() => {
    if (!needsTouched) setBrief((b) => ({ ...b, needs: suggestNeeds(b.job) }));
    if (!careTouched) setBrief((b) => ({ ...b, care: suggestCare(b.job) }));
  }, [brief.job, needsTouched, careTouched]);

  const ready = briefReady(brief);
  const draft = useMemo<Draft | null>(() => (ready ? draftFromBrief(brief) : null), [ready, brief]);
  const idx = STEPS.findIndex((s) => s.id === step);

  const build = async () => {
    if (!draft || busy || readOnly) return;
    await onBuild(draft);
  };

  const set = <K extends keyof Brief>(key: K, value: Brief[K]) => setBrief((b) => ({ ...b, [key]: value }));
  const setNeed = (k: keyof Brief["needs"], v: boolean) => {
    setNeedsTouched(true);
    setBrief((b) => ({ ...b, needs: { ...b.needs, [k]: v } }));
  };

  return (
    <div className="space-y-10">
      <div>
        <div className="mb-1 flex items-center gap-2 text-sm font-medium text-primary">
          <Sparkles aria-hidden="true" className="h-4 w-4 shrink-0" />{" "}
          {fresh ? "Clean slate - nothing is pre-built" : "New agent - nothing is pre-built"}
        </div>
        <h2 className="text-2xl font-semibold tracking-tight sm:text-3xl">{tHC("s30")}</h2>
        <p className="mt-1 max-w-2xl text-sm text-muted-foreground">{tHC("s29")}</p>
      </div>

      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
        <div className="space-y-4">
          {/* Stepper */}
          <ol className="flex flex-wrap gap-1.5" aria-label={tHC("s28")} data-tour="brief-steps">
            {STEPS.map((s, i) => {
              const Icon = s.icon;
              const active = s.id === step;
              const answered = i < idx || (s.id === "job" && ready);
              return (
                <li key={s.id}>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => setStep(s.id)}
                    aria-current={active ? "step" : undefined}
                    className={`inline-flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs transition-colors ${
                      active
                        ? "border-violet-500 bg-violet-600 text-white shadow-sm shadow-violet-500/30"
                        : answered
                          ? "border-emerald-500/40 bg-emerald-500/10 text-foreground"
                          : "border-border/60 text-muted-foreground hover:border-violet-400/60"
                    }`}
                  >
                    {answered && !active ? (
                      <Check aria-hidden="true" className="h-3 w-3 text-emerald-600 dark:text-emerald-400 shrink-0" />
                    ) : (
                      <Icon className="h-3 w-3" />
                    )}{" "}
                    {s.label}
                  </button>
                </li>
              );
            })}
          </ol>

          <div className="rounded-2xl border border-border/60 p-4">
            <AnimatePresence mode="wait">
              <motion.div
                key={step}
                initial={{ opacity: 0, x: 8 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0 }}
                className="space-y-3"
              >
                {step === "job" && (
                  <>
                    <Q
                      title={tHC("s27")}
                      why="The brief is the biggest steering wheel you have. A specific job (who, what, for what result) beats a vague one every time."
                    />
                    <Textarea
                      value={brief.job}
                      rows={4}
                      maxLength={240}
                      disabled={busy || readOnly}
                      aria-label={tHC("s27")}
                      data-tour="brief-job"
                      placeholder={JOB_PLACEHOLDERS[ph]}
                      onChange={(e) => set("job", e.target.value)}
                      className="resize-none text-base"
                    />
                    <div className="text-end text-xs text-muted-foreground">{brief.job.length}/240</div>
                    <div className="space-y-1.5">
                      <div className="text-xs text-muted-foreground">{tHC("s26")}</div>
                      <div className="flex flex-wrap gap-1.5" data-tour="brief-ideas">
                        {JOB_IDEAS.map((idea) => (
                          <button
                            key={idea.label}
                            type="button"
                            disabled={busy || readOnly}
                            onClick={() => set("job", idea.job)}
                            className="rounded-full border border-border/60 px-2.5 py-1 text-xs text-muted-foreground transition-colors hover:border-violet-400/60 hover:text-foreground disabled:opacity-50"
                          >
                            {idea.label}
                          </button>
                        ))}
                      </div>
                    </div>
                  </>
                )}
                {step === "who" && (
                  <>
                    <Q
                      title={tHC("s25")}
                      why="Tone and level of detail follow the reader. 'A busy shop owner' and 'a compliance officer' get very different answers from the same facts."
                      optional
                    />
                    <Input
                      value={brief.audience}
                      maxLength={160}
                      disabled={busy || readOnly}
                      aria-label="Audience"
                      placeholder={tHC("s24")}
                      onChange={(e) => set("audience", e.target.value)}
                    />
                  </>
                )}
                {step === "never" && (
                  <>
                    <Q
                      title={tHC("s23")}
                      why="Boundaries are cheaper than cleanups. This becomes a standing rule in its brief - the single most valuable answer here."
                      optional
                    />
                    <Textarea
                      value={brief.never}
                      rows={3}
                      maxLength={300}
                      disabled={busy || readOnly}
                      aria-label={tHC("s22")}
                      placeholder={tHC("s21")}
                      onChange={(e) => set("never", e.target.value)}
                      className="resize-none"
                    />
                    <div className="flex items-start gap-2 rounded-lg bg-muted/40 p-2.5 text-xs text-muted-foreground">
                      <ShieldCheck aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      <span>{tHC("s20")}</span>
                    </div>
                  </>
                )}
                {step === "needs" && (
                  <>
                    <Q
                      title={tHC("s19")}
                      why="Tools are what turn an answer into an action. Give it only what the job needs - every extra tool is one more thing it can get wrong."
                    />
                    <div className="grid gap-2 sm:grid-cols-2">
                      <Need
                        icon={Globe}
                        label={tHC("s18")}
                        hint="Live search and page reading, with sources"
                        on={brief.needs.web}
                        onChange={(v) => setNeed("web", v)}
                      />
                      <Need
                        icon={Code2}
                        label={tHC("s17")}
                        hint="Maths done by code, not by guessing"
                        on={brief.needs.code}
                        onChange={(v) => setNeed("code", v)}
                      />
                      <Need
                        icon={Webhook}
                        label={tHC("s16")}
                        hint="Read live data straight from a service"
                        on={brief.needs.api}
                        onChange={(v) => setNeed("api", v)}
                      />
                      <Need
                        icon={FileText}
                        label={tHC("s15")}
                        hint="You'll upload them next - it answers from your pages"
                        on={brief.needs.docs}
                        onChange={(v) => setNeed("docs", v)}
                      />
                    </div>
                    {brief.job && !needsTouched && (
                      <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
                        <Lightbulb aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {tHC("s14")}
                      </p>
                    )}
                    <p className="text-xs text-muted-foreground">{tHC("s13")}</p>
                  </>
                )}
                {step === "precision" && (
                  <>
                    <Q
                      title={tHC("s12")}
                      why="Three dials trade speed and cost against certainty. Turn them up when a mistake would be expensive; down when you want speed."
                    />
                    <div>
                      <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        {tHC("s11")}
                      </div>
                      <div className="grid gap-2 sm:grid-cols-3">
                        {CARE.map((c) => (
                          <button
                            key={c.id}
                            type="button"
                            onClick={() => {
                              setCareTouched(true);
                              set("care", c.id);
                            }}
                            className={`rounded-xl border p-2.5 text-start text-sm transition-colors ${pick("amber", brief.care === c.id)}`}
                          >
                            <div className="font-medium">{c.label}</div>
                            <div className="text-xs text-muted-foreground">{c.blurb}</div>
                          </button>
                        ))}
                      </div>
                    </div>
                    <div>
                      <div className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        {tHC("s10")}
                      </div>
                      <div className="grid gap-2 sm:grid-cols-2">
                        {(
                          [
                            [
                              "SEMI_AUTONOMOUS",
                              "Asks first",
                              "Acts freely on safe tools; anything sensitive (sending, paying) waits for you.",
                            ],
                            [
                              "FULLY_AUTONOMOUS",
                              "Runs on its own",
                              "Acts without asking, with a bigger step budget. For jobs you already trust.",
                            ],
                          ] as const
                        ).map(([id, label, blurb]) => (
                          <button
                            key={id}
                            type="button"
                            onClick={() => set("autonomy", id)}
                            className={`rounded-xl border p-2.5 text-start text-sm transition-colors ${pick("sky", brief.autonomy === id)}`}
                          >
                            <div className="font-medium">{label}</div>
                            <div className="text-xs text-muted-foreground">{blurb}</div>
                          </button>
                        ))}
                      </div>
                    </div>
                    <div>
                      <label
                        htmlFor="brief-creativity"
                        className="mb-1 flex items-center justify-between text-xs font-medium uppercase tracking-wide text-muted-foreground"
                      >
                        <span>Creativity</span>
                        <span className="normal-case tracking-normal">
                          {brief.creativity < 0.25
                            ? "same answer every time"
                            : brief.creativity < 0.6
                              ? "steady"
                              : "varied wording"}
                        </span>
                      </label>
                      <input
                        id="brief-creativity"
                        type="range"
                        min={0}
                        max={1}
                        step={0.05}
                        value={brief.creativity}
                        onChange={(e) => set("creativity", Number(e.target.value))}
                        className="w-full"
                      />
                    </div>
                  </>
                )}
              </motion.div>
            </AnimatePresence>

            <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-border/60 pt-3">
              <Button variant="ghost" size="sm" disabled={idx === 0 || busy} onClick={() => setStep(STEPS[idx - 1].id)}>
                <ArrowLeft aria-hidden="true" className="me-1 h-3.5 w-3.5 shrink-0" /> Back
              </Button>
              {idx < STEPS.length - 1 ? (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy || (step === "job" && !ready)}
                  onClick={() => setStep(STEPS[idx + 1].id)}
                >
                  Next <ArrowRight aria-hidden="true" className="ms-1 h-3.5 w-3.5 shrink-0" />
                </Button>
              ) : null}
              <div className="ms-auto flex gap-2">
                <Button variant="ghost" size="sm" disabled={busy || readOnly} onClick={onBlank}>
                  <PenLine aria-hidden="true" className="me-1.5 h-3.5 w-3.5 shrink-0" /> {tHC("s9")}
                </Button>
                <Button
                  size="sm"
                  className={TONES.violet.cta}
                  onClick={build}
                  disabled={!ready || busy || readOnly}
                  data-tour="build-btn"
                >
                  {busy ? (
                    <Loader2 aria-hidden="true" className="me-1.5 h-4 w-4 animate-spin shrink-0" />
                  ) : (
                    <Sparkles aria-hidden="true" className="me-1.5 h-4 w-4 shrink-0" />
                  )}
                  {busy ? "Assembling…" : "Build my agent"}
                </Button>
              </div>
            </div>
          </div>
        </div>

        {/* The agent assembling itself from the answers. */}
        <div
          className="rounded-2xl border border-violet-500/20 bg-gradient-to-br from-violet-500/5 via-muted/30 to-sky-500/5 p-4"
          aria-live="polite"
          data-tour="blueprint-preview"
        >
          <div className="mb-3 flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <Brain aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {tHC("s8")}
          </div>
          <AnimatePresence mode="wait">
            {draft ? (
              <motion.div
                key="draft"
                initial={{ opacity: 0, y: 6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0 }}
                className="space-y-3"
              >
                <div className="text-lg font-semibold leading-tight">{draft.spec.name}</div>
                <div className="flex flex-wrap items-center gap-1.5">
                  <Badge variant="outline">
                    <Brain aria-hidden="true" /> {String(draft.spec.reasoningDepth).toLowerCase()} thinking
                  </Badge>
                  <Badge variant="outline">
                    {draft.spec.autonomy === "FULLY_AUTONOMOUS" ? "runs on its own" : "asks before sensitive acts"}
                  </Badge>
                  <Badge variant="outline">
                    <ShieldCheck aria-hidden="true" /> {tHC("s7")}
                  </Badge>
                </div>
                <div>
                  <div className="mb-1 flex items-center gap-1.5 text-xs text-muted-foreground">
                    <Hand aria-hidden="true" className="h-3 w-3 shrink-0" /> {tHC("s6")}
                  </div>
                  <div className="flex flex-wrap gap-1.5">
                    {draft.spec.tools.map((t) => (
                      <Badge key={t} variant="secondary">
                        {toolLabel(t)}
                      </Badge>
                    ))}
                  </div>
                </div>
                {brief.needs.docs && (
                  <div className="flex items-start gap-2 rounded-lg bg-background/70 p-2.5 text-xs text-muted-foreground">
                    <FileText aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>
                      {tHC("s5")} <b className="font-medium text-foreground">{draft.docIdea}</b>.
                    </span>
                  </div>
                )}
                <div className="flex items-start gap-2 rounded-lg bg-background/70 p-2.5 text-xs text-muted-foreground">
                  <Plug aria-hidden="true" className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                  <span>
                    {tHC("s4")} <b className="font-medium text-foreground">&ldquo;{draft.mcpQuery}&rdquo;</b>.
                  </span>
                </div>
                <div className="rounded-lg border border-dashed border-border/70 p-2.5">
                  <div className="mb-1 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
                    {tHC("s3")}
                  </div>
                  <div className="line-clamp-6 whitespace-pre-line font-mono text-[11px] leading-relaxed text-muted-foreground">
                    {draft.spec.mission}
                  </div>
                </div>
              </motion.div>
            ) : (
              <motion.div
                key="empty"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                className="flex min-h-[260px] flex-col items-center justify-center gap-2 text-center text-sm text-muted-foreground"
              >
                <Sparkles aria-hidden="true" className="h-6 w-6 shrink-0" />
                <p className="max-w-[16rem]">{tHC("s2")}</p>
              </motion.div>
            )}
          </AnimatePresence>
        </div>
      </div>

      <WhyAgentic />
    </div>
  );
}

function Q({ title, why, optional }: { title: string; why: string; optional?: boolean }) {
  const tHC = useTranslations("hcComponentsStudioGenesis");
  return (
    <div>
      <div className="text-base font-semibold">
        {title} {optional && <span className="text-xs font-normal text-muted-foreground">optional</span>}
      </div>
      <p className="mt-0.5 text-xs text-muted-foreground">
        <b className="font-medium text-foreground">{tHC("s1")}</b> {why}
      </p>
    </div>
  );
}

function Need({
  icon: Icon,
  label,
  hint,
  on,
  onChange,
}: {
  icon: typeof Globe;
  label: string;
  hint: string;
  on: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className={`flex items-start gap-3 rounded-xl border p-3 text-sm transition-colors ${pick("emerald", on)}`}>
      <Switch
        className={`mt-0.5 ${on ? "bg-emerald-600" : ""}`}
        checked={on}
        aria-label={label}
        onCheckedChange={onChange}
      />
      <div>
        <div className="flex items-center gap-1.5 font-medium">
          <Icon className="h-3.5 w-3.5" /> {label}
        </div>
        <div className="text-xs text-muted-foreground">{hint}</div>
      </div>
    </div>
  );
}
