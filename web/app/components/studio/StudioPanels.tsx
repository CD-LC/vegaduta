"use client";

import Link from "next/link";
import Logo from "../Logo";
import {
  Bot,
  Plus,
  Workflow as WorkflowIcon,
  Rocket,
  PenTool,
  Play,
  Loader2,
  Users,
  Clock,
  Activity,
  Compass,
  ArrowRight,
  Check,
  BookOpen,
  Home,
  RefreshCw,
  Clapperboard,
} from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import type { DemoSession } from "../try/demoClient";
import { STAGE_TONE, TONES, pick } from "./studioTheme";
import { useTranslations } from "../../lib/i18n";

// Presentational pieces of the /try studio (TryStudio owns all state): the site
// header, the studio's status bar, the four-stage rail, the next-step nudge and
// the empty Run stage.

export type Stage = "design" | "run" | "chain" | "deploy";

export const STAGES: { id: Stage; label: string; icon: typeof PenTool; blurb: string }[] = [
  { id: "design", label: "Build", icon: PenTool, blurb: "Brief, hands, documents, connectors." },
  { id: "run", label: "Run", icon: Play, blurb: "Give it a job. Watch it work." },
  { id: "chain", label: "Chain", icon: WorkflowIcon, blurb: "Agents in sequence, you in the loop." },
  { id: "deploy", label: "Deploy", icon: Rocket, blurb: "A link, a Telegram bot, your workspace." },
];

/** Brand + a way out: the winged emblem beside the wordmark always links home. */
export function StudioSiteHeader({ signupHref }: { signupHref: string }) {
    const tHC = useTranslations("hcComponentsStudioStudioPanels");
  return (
    <header
      className="mb-8 flex items-center justify-between gap-3 border-b border-border/50 pb-3"
      data-tour="site-header"
    >
      <Link
        href="/"
        aria-label={tHC("s11")}
        className="inline-flex items-center rounded-lg outline-none focus-visible:ring-[3px] focus-visible:ring-ring/40"
      >
        <Logo size={34} />
      </Link>
      <nav aria-label="Site" className="flex items-center gap-1 text-sm">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
        >
          <Home aria-hidden="true" className="h-4 w-4 shrink-0" /> Home
        </Link>
        <Link
          href="/login"
          className="hidden rounded-lg px-3 py-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground sm:inline-flex"
        >
          Sign in
        </Link>
        <Link
          href={signupHref}
          className={`inline-flex items-center rounded-lg px-3.5 py-1.5 text-sm font-medium ${TONES.violet.cta}`}
        >
          {tHC("s10")}
        </Link>
      </nav>
    </header>
  );
}

/** Studio header: what this space is, how much is left, and the way out. */
export function StudioStatusBar({
  session,
  readOnly,
  resetting,
  onTour,
  onShowtime,
  onGuide,
  onRestart,
}: {
  session: DemoSession;
  readOnly: boolean;
  resetting: boolean;
  onTour: () => void;
  onShowtime: () => void;
  onGuide: () => void;
  onRestart: () => void;
}) {
    const tHC = useTranslations("hcComponentsStudioStudioPanels");
  return (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-2 rounded-2xl border border-border/60 bg-background/60 px-4 py-3 text-sm backdrop-blur">
      <div className="flex items-center gap-2 font-medium">
        <Users aria-hidden="true" className="h-4 w-4 shrink-0" /> {tHC("s9")}
        <Badge variant="outline" className="text-[11px]">
          {tHC("s8")}
        </Badge>
      </div>
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Activity aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {session.messagesUsed} run
        {session.messagesUsed === 1 ? "" : "s"} so far
      </div>
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Clock aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
        {readOnly
          ? "view-only now"
          : session.editableUntil
            ? `hands-on until ${new Date(session.editableUntil).toLocaleString()}`
            : `${session.daysRemaining} days left`}
      </div>
      <Button size="sm" className={`ms-auto ${TONES.violet.cta}`} onClick={onTour} data-tour="tour-btn">
        <Compass aria-hidden="true" className="me-1.5 h-3.5 w-3.5 shrink-0" /> {tHC("s7")}
      </Button>
      <Button variant="outline" size="sm" onClick={onShowtime} data-tour="showtime-header">
        <Clapperboard aria-hidden="true" className="me-1.5 h-3.5 w-3.5 shrink-0" /> Showtime
      </Button>
      <Button variant="ghost" size="sm" onClick={onGuide}>
        <BookOpen aria-hidden="true" className="me-1.5 h-3.5 w-3.5 shrink-0" /> {tHC("s6")}
      </Button>
      <Button variant="outline" size="sm" onClick={onRestart} disabled={resetting}>
        {resetting ? (
          <Loader2 aria-hidden="true" className="me-1.5 h-3.5 w-3.5 animate-spin shrink-0" />
        ) : (
          <RefreshCw aria-hidden="true" className="me-1.5 h-3.5 w-3.5 shrink-0" />
        )}{" "}
        {tHC("s5")}
      </Button>
    </div>
  );
}

/** The four stages, each a button; done stages carry a check. */
export function StageRail({ stage, done, onStage }: { stage: Stage; done: Set<Stage>; onStage: (s: Stage) => void }) {
  return (
    <div className="mb-6 grid grid-cols-2 gap-2 sm:grid-cols-4" data-tour="stages">
      {STAGES.map((s, i) => {
        const Icon = s.icon;
        const active = stage === s.id;
        return (
          <button
            key={s.id}
            type="button"
            onClick={() => onStage(s.id)}
            data-tour={`stage-${s.id}`}
            aria-current={active ? "step" : undefined}
            className={`rounded-xl border p-3 text-start transition-all ${pick(STAGE_TONE[s.id], active)} ${active ? "-translate-y-0.5" : ""}`}
          >
            <div className="flex items-center gap-2 text-sm font-medium">
              <span
                className={`inline-flex h-6 w-6 items-center justify-center rounded-full text-xs ${active ? TONES[STAGE_TONE[s.id]].solid : done.has(s.id) ? "bg-emerald-600 text-white" : TONES[STAGE_TONE[s.id]].soft}`}
              >
                {done.has(s.id) && !active ? <Check aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> : i + 1}
              </span>
              <Icon className={`h-4 w-4 ${TONES[STAGE_TONE[s.id]].text}`} /> {s.label}
            </div>
            <div className="mt-1 text-xs text-muted-foreground">{s.blurb}</div>
          </button>
        );
      })}
    </div>
  );
}

/** Next-step nudge, in the order the rail promises. */
export function NextStepNudge({
  stage,
  launching,
  signupHref,
  onStage,
}: {
  stage: Stage;
  launching: boolean;
  signupHref: string;
  onStage: (s: Stage) => void;
}) {
    const tHC = useTranslations("hcComponentsStudioStudioPanels");
  return (
    <div className="mt-6 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border/60 p-4 text-sm">
      <span className="text-muted-foreground">
        {stage === "design" &&
          (launching
            ? "Describe the job - anything at all."
            : "Armed with documents and connectors? Give it a job next.")}
        {stage === "run" && "Seen it work? Chain two agents and put yourself in the loop."}
        {stage === "chain" && "Now put it where people are."}
        {stage === "deploy" && "Everything here is copied into your workspace when you sign up."}
      </span>
      {stage !== "deploy" ? (
        <Button
          variant="outline"
          size="sm"
          onClick={() => onStage(STAGES[STAGES.findIndex((s) => s.id === stage) + 1].id)}
        >
          Next <ArrowRight aria-hidden="true" className="ms-1 h-3.5 w-3.5 shrink-0" />
        </Button>
      ) : (
        <Button size="sm" className={TONES.violet.cta} asChild>
          <Link href={signupHref}>
            {tHC("s4")} <ArrowRight aria-hidden="true" className="ms-1 h-3.5 w-3.5 shrink-0" />
          </Link>
        </Button>
      )}
    </div>
  );
}

export function EmptyState({ onNew }: { onNew: () => void }) {
    const tHC = useTranslations("hcComponentsStudioStudioPanels");
  return (
    <div className="rounded-xl border border-dashed border-border/70 p-8 text-center">
      <Bot aria-hidden="true" className="mx-auto mb-2 h-8 w-8 text-muted-foreground shrink-0" />
      <div className="font-medium">{tHC("s3")}</div>
      <p className="mb-4 text-sm text-muted-foreground">{tHC("s2")}</p>
      <Button onClick={onNew}>
        <Plus aria-hidden="true" className="me-2 h-4 w-4 shrink-0" /> {tHC("s1")}
      </Button>
    </div>
  );
}
