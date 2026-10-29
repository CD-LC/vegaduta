"use client";

import {
  AlertTriangle,
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  ChevronDown,
  ChevronRight,
  Copy,
  Pause,
  Pencil,
  Play,
  Trash2,
  Zap,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import {
  describeSchedule,
  draftFromTask,
  formatRunTime,
  statusTone,
  type ScheduledTask,
} from "../../lib/scheduledTasks";
import ConfirmDelete from "../dataTables/ConfirmDelete";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import ScheduledTaskRuns from "./ScheduledTaskRuns";
import { nextSort, type TaskSort, type TaskSortKey } from "./scheduledTaskSort";

interface Props {
  tasks: ScheduledTask[];
  agentNames: ReadonlyMap<string, string>;
  atLimit: boolean;
  maxTasks: number;
  busyId: string | null;
  openRuns: string | null;
  runsKey: number;
  sort: TaskSort;
  onSortChange: (sort: TaskSort) => void;
  onRunNow: (task: ScheduledTask) => void;
  onPause: (task: ScheduledTask) => void;
  onResume: (task: ScheduledTask) => void;
  onEdit: (task: ScheduledTask) => void;
  onDuplicate: (task: ScheduledTask) => void;
  onRemove: (task: ScheduledTask) => void;
  onToggleRuns: (taskId: string | null) => void;
}

/** A sortable column header: a real button inside the th, with aria-sort on the th. */
function SortHead({
  sortKey,
  sort,
  onSortChange,
  children,
  className,
}: {
  sortKey: TaskSortKey;
  sort: TaskSort;
  onSortChange: (s: TaskSort) => void;
  children: string;
  className?: string;
}) {
  const active = sort.key === sortKey;
  const Icon = !active ? ArrowUpDown : sort.dir === "asc" ? ArrowUp : ArrowDown;
  return (
    <TableHead className={className} aria-sort={active ? (sort.dir === "asc" ? "ascending" : "descending") : "none"}>
      <button
        type="button"
        onClick={() => onSortChange(nextSort(sort, sortKey))}
        className="-mx-1 inline-flex items-center gap-1 rounded px-1 py-0.5 text-start font-medium outline-none hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring"
      >
        {children}
        <Icon size={12} aria-hidden="true" className={active ? "text-foreground" : "text-muted-foreground"} />
      </button>
    </TableHead>
  );
}

/**
 * The scheduled-tasks list as a table: one row per task (name + status, schedule, next / last run, runs, actions) and
 * a full-width detail row under it (prompt, delivery, warnings and the expandable run history). Sorting is by column
 * header; paging and filtering stay in the panel.
 */
export default function ScheduledTasksTable({
  tasks,
  agentNames,
  atLimit,
  maxTasks,
  busyId,
  openRuns,
  runsKey,
  sort,
  onSortChange,
  onRunNow,
  onPause,
  onResume,
  onEdit,
  onDuplicate,
  onRemove,
  onToggleRuns,
}: Props) {
  const t = useTranslations("scheduledTasks");
  const ts = useTranslations("scheduledPage");
  return (
    <Card className="gap-0 overflow-hidden p-0">
      <Table data-testid="scheduled-task-list" className="min-w-[56rem]">
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <SortHead sortKey="name" sort={sort} onSortChange={onSortChange}>
              {ts("colTask")}
            </SortHead>
            <TableHead>{ts("colSchedule")}</TableHead>
            <SortHead sortKey="nextRun" sort={sort} onSortChange={onSortChange}>
              {t("nextRunLabel")}
            </SortHead>
            <SortHead sortKey="lastRun" sort={sort} onSortChange={onSortChange}>
              {t("lastRunLabel")}
            </SortHead>
            <SortHead sortKey="runCount" sort={sort} onSortChange={onSortChange}>
              {t("runsLabel")}
            </SortHead>
            <TableHead className="text-end">{ts("colActions")}</TableHead>
          </TableRow>
        </TableHeader>
        {tasks.map((task) => {
          const description = describeSchedule(draftFromTask(task));
          const dayText =
            task.frequency === "weekly" ? t(`day_${description.params.day}`) : (description.params.day ?? "");
          const open = openRuns === task.id;
          const busy = busyId === task.id;
          return (
            // One tbody per task keeps the main row and its detail row together (and valid HTML).
            <TableBody key={task.id} className="border-b border-border last:border-0">
              <TableRow className="border-b-0 align-top">
                <TableCell className="min-w-48 max-w-72 whitespace-normal">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="truncate text-sm font-semibold text-foreground">{task.name}</h3>
                    <Badge variant="state" state={task.enabled ? "var(--state-success)" : "var(--state-idle)"}>
                      {t(task.enabled ? "stateActive" : "statePaused")}
                    </Badge>
                    {task.lastStatus && (
                      <Badge variant="state" state={`var(--state-${statusTone(task.lastStatus)})`}>
                        {t(`runStatus_${task.lastStatus}`)}
                      </Badge>
                    )}
                  </div>
                  {agentNames.get(task.agentId) && (
                    <p className="mt-1 text-xs text-muted-foreground">
                      {t("agentShort", { name: agentNames.get(task.agentId) ?? "" })}
                    </p>
                  )}
                </TableCell>
                <TableCell className="whitespace-normal text-sm text-foreground">
                  {t(description.key, { ...description.params, day: dayText })}
                </TableCell>
                <TableCell className="whitespace-normal text-xs text-foreground">
                  {task.enabled && task.nextRunAt ? formatRunTime(task.nextRunAt, task.timezone) : t("nextRunNone")}
                </TableCell>
                <TableCell className="whitespace-normal text-xs text-foreground">
                  {task.lastRunAt ? formatRunTime(task.lastRunAt, task.timezone) : t("neverRun")}
                </TableCell>
                <TableCell className="whitespace-normal text-xs text-foreground">
                  {task.maxRuns ? t("runsOfMax", { count: task.runCount, max: task.maxRuns }) : task.runCount}
                  {task.expiresAt ? ` - ${t("endsOn", { date: task.expiresAt.slice(0, 10) })}` : ""}
                </TableCell>
                <TableCell className="whitespace-normal">
                  <div className="flex flex-wrap justify-end gap-2">
                    <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => onRunNow(task)}>
                      <Zap size={14} aria-hidden="true" /> {t("runNow")}
                    </Button>
                    {task.enabled ? (
                      <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => onPause(task)}>
                        <Pause size={14} aria-hidden="true" /> {t("pause")}
                      </Button>
                    ) : (
                      <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => onResume(task)}>
                        <Play size={14} aria-hidden="true" /> {t("resume")}
                      </Button>
                    )}
                    <Button type="button" size="sm" variant="outline" disabled={busy} onClick={() => onEdit(task)}>
                      <Pencil size={14} aria-hidden="true" /> {t("edit")}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      disabled={busy || atLimit}
                      title={atLimit ? t("limitReached", { max: maxTasks }) : undefined}
                      onClick={() => onDuplicate(task)}
                    >
                      <Copy size={14} aria-hidden="true" /> {t("duplicate")}
                    </Button>
                    <ConfirmDelete
                      trigger={
                        <Button type="button" size="sm" variant="outline" disabled={busy}>
                          <Trash2 size={14} aria-hidden="true" /> {t("delete")}
                        </Button>
                      }
                      title={t("deleteTitle", { name: task.name })}
                      body={t("deleteBody")}
                      confirmLabel={t("deleteConfirm")}
                      cancelLabel={t("cancel")}
                      onConfirm={() => onRemove(task)}
                    />
                  </div>
                </TableCell>
              </TableRow>
              <TableRow className="align-top hover:bg-transparent">
                <TableCell colSpan={6} className="space-y-2 whitespace-normal pt-0">
                  <p className="line-clamp-2 text-xs text-muted-foreground">{task.prompt}</p>
                  <p className="text-xs text-muted-foreground">
                    <span className="font-medium">{t("deliveryShort")}: </span>
                    <span className="text-foreground">
                      {t(`delivery_${task.delivery}`)}
                      {task.deliveryTo ? ` (${task.deliveryTo})` : ""}
                    </span>
                  </p>
                  {!task.enabled && task.disabledReason && (
                    <Alert variant="warning">
                      <AlertTriangle aria-hidden="true" />
                      <AlertDescription>{task.disabledReason}</AlertDescription>
                    </Alert>
                  )}
                  {task.enabled && task.consecutiveFailures > 0 && (
                    <p className="text-xs text-destructive">
                      {t("failuresInARow", { count: task.consecutiveFailures })}
                    </p>
                  )}
                  <div>
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      aria-expanded={open}
                      onClick={() => onToggleRuns(open ? null : task.id)}
                    >
                      {open ? (
                        <ChevronDown size={14} aria-hidden="true" />
                      ) : (
                        <ChevronRight size={14} aria-hidden="true" />
                      )}{" "}
                      {t(open ? "historyHide" : "historyShow")}
                    </Button>
                    {open && (
                      <ScheduledTaskRuns
                        taskId={task.id}
                        timezone={task.timezone}
                        refreshKey={runsKey}
                        runCount={task.runCount}
                        taskStatus={task.lastStatus}
                      />
                    )}
                  </div>
                </TableCell>
              </TableRow>
            </TableBody>
          );
        })}
      </Table>
    </Card>
  );
}
