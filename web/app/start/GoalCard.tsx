"use client";

import {
  AlertTriangle,
  CheckCircle2,
  FileSpreadsheet,
  GraduationCap,
  Loader2,
  Lock,
  PenLine,
  Search,
  Wand2,
  Workflow,
  type LucideIcon,
} from "lucide-react";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { cn } from "../lib/utils";
import { goalBodyKey, goalTitleKey, MISSING_NOTE_KEY, STATUS_KEY, UNCHECKED_NOTE_KEY } from "./copy";
import type { GoalDef, GoalId } from "./goals";
import type { CardState } from "./prereq";

const GOAL_ICON: Record<GoalId, LucideIcon> = {
  learn: GraduationCap,
  research: Search,
  analyze: FileSpreadsheet,
  automate: Workflow,
  draft: PenLine,
  build: Wand2,
};

const STATUS_ICON: Record<CardState["kind"], LucideIcon> = {
  checking: Loader2,
  ready: CheckCircle2,
  reduced: AlertTriangle,
  blocked: AlertTriangle,
  locked: Lock,
};

type T = (key: string, params?: Record<string, string | number>) => string;

/**
 * One goal card on step 1. Shows the goal in plain words, its honest
 * prerequisite state (a card whose prerequisite is missing says what is
 * missing, and the person can still choose it - the next step explains the
 * limits and links to setup), and a "Choose this" button. The card itself is
 * not a click target: one real button per card keeps keyboard order and the
 * accessible name obvious.
 */
export default function GoalCard({
  goal,
  state,
  onChoose,
  t,
}: {
  goal: GoalDef;
  state: CardState;
  onChoose: (id: GoalId) => void;
  t: T;
}) {
  const Icon = GOAL_ICON[goal.id];
  const StatusIcon = STATUS_ICON[state.kind];
  const titleId = `start-goal-${goal.id}-title`;
  const statusId = `start-goal-${goal.id}-status`;
  const notes = [
    ...state.missing.map((item) => t(MISSING_NOTE_KEY[item])),
    ...state.unchecked.map((item) => t(UNCHECKED_NOTE_KEY[item])),
  ];
  if (state.fellBack) notes.unshift(t("noteTutorOff"));
  if (state.kind === "locked") notes.push(t("lockedNote"));

  return (
    <Card className="gap-3 p-5" data-testid={`start-goal-${goal.id}`} data-state={state.kind}>
      <div className="flex items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Icon className="size-5" aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <h3 id={titleId} className="text-base font-semibold tracking-tight text-foreground">
            {t(goalTitleKey(goal.id))}
          </h3>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">{t(goalBodyKey(goal.id))}</p>
        </div>
      </div>

      <p
        id={statusId}
        className={cn(
          "flex items-center gap-1.5 text-xs font-medium",
          state.kind === "ready" ? "text-primary" : "text-muted-foreground"
        )}
      >
        <StatusIcon className={cn("size-3.5 shrink-0", state.kind === "checking" && "motion-safe:animate-spin")} aria-hidden="true" />
        {t(STATUS_KEY[state.kind])}
      </p>

      {notes.length > 0 && (
        <ul className="m-0 list-none space-y-1 p-0 text-xs leading-relaxed text-muted-foreground">
          {notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}

      <div className="mt-auto pt-1">
        <Button
          type="button"
          size="sm"
          variant={state.kind === "ready" ? "default" : "outline"}
          onClick={() => onChoose(goal.id)}
          id={`start-goal-${goal.id}-btn`}
          aria-labelledby={`start-goal-${goal.id}-btn ${titleId}`}
          aria-describedby={statusId}
        >
          {t("choose")}
        </Button>
      </div>
    </Card>
  );
}
