"use client";

import Link from "next/link";
import { CalendarClock, ExternalLink, ListChecks, PlayCircle, ShieldCheck, X as CloseIcon } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import type { WorkflowPlan } from "./workflowPlan";

/**
 * "What this workflow does" (nlflow W4) - shown under the canvas after the AI
 * builder produced or changed the graph, and reopened from the describe bar.
 *
 * It replaces the transient toasts the builder's notes used to be. Everything
 * in it comes from the generate-graph response (see workflowPlan.ts): the
 * step-by-step explanation the server computed from the graph, what still
 * has to be connected, the approval steps the server put in front of sends,
 * and a schedule the description implied. Against an older core only the
 * notes exist and only that section renders.
 *
 * The panel never changes the graph. Its two actions are handed in by the
 * canvas: switching the suggested schedule on (existing schedule endpoint,
 * ACTIVE workflows only) and starting a draft test run with sends simulated.
 */

type Translator = (key: string, values?: Record<string, string | number>) => string;

export type WorkflowPlanPanelProps = {
  plan: WorkflowPlan;
  /** Selects the node on the canvas and scrolls it into view. */
  onFocusNode: (nodeId: string) => void;
  /** False for a step whose node was deleted since the plan was produced. */
  nodeExists?: (nodeId: string) => boolean;
  onClose: () => void;
  /** The workflow is ACTIVE - the only state a schedule can be switched on in. */
  workflowActive: boolean;
  /** Caller may manage schedules (same gate as the trigger strip). */
  canManageSchedule: boolean;
  scheduleBusy?: boolean;
  /** The suggested cron is the workflow's enabled schedule right now. */
  scheduleOn?: boolean;
  onTurnOnSchedule?: () => void;
  /** Absent when the caller may not run drafts; the button is then hidden. */
  onTestRun?: () => void;
  testRunBusy?: boolean;
  /** Sends simulated by the last dry run on screen, or null when there is none. */
  dryRunSends?: number | null;
};

const SECTION_TITLE = "flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground";

export default function WorkflowPlanPanel({
  plan,
  onFocusNode,
  nodeExists,
  onClose,
  workflowActive,
  canManageSchedule,
  scheduleBusy = false,
  scheduleOn = false,
  onTurnOnSchedule,
  onTestRun,
  testRunBusy = false,
  dryRunSends = null,
}: WorkflowPlanPanelProps) {
  const t = useTranslations("workflowDescribe") as Translator;
  const exists = (nodeId: string) => !!nodeId && (nodeExists ? nodeExists(nodeId) : true);

  return (
    <section
      data-testid="workflow-plan-panel"
      aria-label={t("planTitle")}
      className="ax-wf-plan-panel max-h-72 shrink-0 overflow-y-auto rounded-lg border border-border bg-card p-4 text-sm text-foreground"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold tracking-tight text-foreground">{t("planTitle")}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {plan.mode === "edit" ? t("planModeEdit") : t("planModeCreate")}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {onTestRun && (
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={onTestRun}
              disabled={testRunBusy}
              title={t("planTestRunHint")}
              data-testid="workflow-plan-test-run"
            >
              <PlayCircle size={14} />
              {testRunBusy ? t("planTestRunning") : t("planTestRun")}
            </Button>
          )}
          <button
            type="button"
            className="flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
            onClick={onClose}
            aria-label={t("planClose")}
            data-testid="workflow-plan-close"
          >
            <CloseIcon size={16} />
          </button>
        </div>
      </div>

      {dryRunSends !== null && dryRunSends !== undefined && (
        <p className="mt-2 text-xs text-muted-foreground" data-testid="workflow-plan-dry-run">
          {t("planDryRunResult", { count: dryRunSends })}
        </p>
      )}

      {plan.steps.length > 0 && (
        <ol className="mt-3 space-y-1.5" data-testid="workflow-plan-steps">
          {plan.steps.map((step, i) => {
            const clickable = exists(step.nodeId);
            const body = (
              <>
                <span className="mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-full bg-muted text-[11px] font-medium text-foreground">
                  {i + 1}
                </span>
                <span className="min-w-0 text-start">{step.sentence}</span>
              </>
            );
            return (
              <li key={`${step.nodeId}-${i}`}>
                {clickable ? (
                  <button
                    type="button"
                    className="flex w-full items-start gap-2 rounded-md border-0 bg-transparent p-1 text-sm text-foreground shadow-none transition-colors hover:bg-accent"
                    onClick={() => onFocusNode(step.nodeId)}
                    aria-label={t("planStepFocus", { n: i + 1, sentence: step.sentence })}
                    data-testid="workflow-plan-step"
                  >
                    {body}
                  </button>
                ) : (
                  <div className="flex items-start gap-2 p-1" data-testid="workflow-plan-step">
                    {body}
                  </div>
                )}
              </li>
            );
          })}
        </ol>
      )}

      {plan.insertedApprovals.length > 0 && (
        <div className="mt-3" data-testid="workflow-plan-approvals">
          <h3 className={SECTION_TITLE}>
            <ShieldCheck size={13} />
            {t("planApprovalsTitle")}
          </h3>
          <p className="mt-1 text-sm text-muted-foreground">{t("planApprovalsBody")}</p>
          <div className="mt-1.5 flex flex-wrap gap-2">
            {plan.insertedApprovals
              .filter((a) => exists(a.approvalNodeId))
              .map((a, i) => (
                <Button
                  key={a.approvalNodeId}
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() => onFocusNode(a.approvalNodeId)}
                  data-testid="workflow-plan-approval"
                >
                  {t("planApprovalShow", { n: i + 1 })}
                </Button>
              ))}
          </div>
        </div>
      )}

      {plan.requirements.length > 0 && (
        <div className="mt-3" data-testid="workflow-plan-requirements">
          <h3 className={SECTION_TITLE}>
            <ListChecks size={13} />
            {t("planNeedsTitle")}
          </h3>
          <ul className="mt-1.5 space-y-1.5">
            {plan.requirements.map((req, i) => (
              <li key={i} className="flex items-start gap-2 text-sm text-foreground">
                <span className="mt-1.5 size-1.5 shrink-0 rounded-full bg-primary" />
                <span className="min-w-0">
                  {req.message}{" "}
                  {req.href && (
                    // New tab: the canvas may hold unsaved edits.
                    <Link
                      href={req.href}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center gap-1 font-medium text-primary underline-offset-2 hover:underline"
                      data-testid="workflow-plan-requirement-link"
                    >
                      {t("planNeedsOpen")}
                      <ExternalLink size={12} />
                    </Link>
                  )}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {plan.schedule && (
        <div className="mt-3" data-testid="workflow-plan-schedule">
          <h3 className={SECTION_TITLE}>
            <CalendarClock size={13} />
            {t("planScheduleTitle")}
          </h3>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <span className="text-sm text-foreground">
              {t("planScheduleLine", { label: plan.schedule.label, timezone: plan.schedule.timezone })}
            </span>
            {scheduleOn ? (
              <span className="text-xs text-muted-foreground">{t("planScheduleOn")}</span>
            ) : !workflowActive ? (
              <span className="text-xs text-muted-foreground">{t("planScheduleAfterActivate")}</span>
            ) : !canManageSchedule || !onTurnOnSchedule ? (
              <span className="text-xs text-muted-foreground">{t("planScheduleAdminOnly")}</span>
            ) : (
              <Button
                type="button"
                size="sm"
                variant="outline"
                onClick={onTurnOnSchedule}
                disabled={scheduleBusy}
                data-testid="workflow-plan-schedule-on"
              >
                {scheduleBusy ? t("planScheduleTurningOn") : t("planScheduleTurnOn")}
              </Button>
            )}
          </div>
        </div>
      )}

      {plan.notes.length > 0 && (
        <div className="mt-3" data-testid="workflow-plan-notes">
          <h3 className={SECTION_TITLE}>{t("planNotesTitle")}</h3>
          <ul className="mt-1.5 space-y-1.5">
            {plan.notes.map((note, i) => (
              <li key={i} className="flex gap-2 text-sm text-muted-foreground">
                <span className="mt-1.5 size-1 shrink-0 rounded-full bg-primary" />
                {note}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
