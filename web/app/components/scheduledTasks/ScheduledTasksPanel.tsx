"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, CalendarClock, Plus, Search } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import {
  draftFromTask,
  isRunInFlight,
  RUNNING_POLL_MAX_TICKS,
  RUNNING_POLL_MS,
  type ScheduledTask,
  type ScheduledTasksConfig,
} from "../../lib/scheduledTasks";
import { SCHEDULED_TASK_TEMPLATES } from "../../lib/scheduledTaskTemplates";
import EmptyState from "../EmptyState";
import PaginationControls from "../PaginationControls";
import { ListSkeleton } from "../tenantLists/ListStates";
import { usePagination } from "../../lib/usePagination";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import ScheduledTaskForm, { type AgentOption, type TaskSeed } from "./ScheduledTaskForm";
import ScheduledTaskExplainer from "./ScheduledTaskExplainer";
import ScheduledTasksTable from "./ScheduledTasksTable";
import { DEFAULT_TASK_SORT, sortTasks, type TaskSort } from "./scheduledTaskSort";
import ScheduledTasksSummary from "./ScheduledTasksSummary";
import { filterTasks, taskStatusCounts, type TaskFilter } from "./scheduledTaskStats";

/**
 * /scheduled: the list of the caller's scheduled tasks, a create / edit form, per-task run history, pause / resume
 * and run-now.
 *
 * Rendering order follows what the server says: GET /api/scheduled-tasks/capabilities is always readable, so the
 * page can tell "not enabled in this deployment" (flag off) apart from a real load failure, and only calls the rest
 * of the API when the feature is usable.
 */

const NOT_ENABLED: ScheduledTasksConfig = {
  enabled: false,
  maxTasks: 0,
  minIntervalMinutes: 15,
  maxPromptChars: 2000,
  deliveryChannels: ["in_app"],
};

type Editing = ScheduledTask | "new" | null;

// Shared with the run history (ScheduledTaskRuns), which polls on the same cadence; re-exported for callers/tests.
export { RUNNING_POLL_MS, RUNNING_POLL_MAX_TICKS, isRunInFlight };

function byCreated(a: ScheduledTask, b: ScheduledTask) {
  return (b.createdAt || "").localeCompare(a.createdAt || "");
}

export default function ScheduledTasksPanel({ onLoaded }: { onLoaded?: () => void }) {
  const t = useTranslations("scheduledTasks");
  const [cfg, setCfg] = useState<ScheduledTasksConfig | null>(null);
  const [failed, setFailed] = useState(false);
  const [tasks, setTasks] = useState<ScheduledTask[]>([]);
  const [agents, setAgents] = useState<AgentOption[]>([]);
  const [editing, setEditing] = useState<Editing>(null);
  const [openRuns, setOpenRuns] = useState<string | null>(null);
  const [runsKey, setRunsKey] = useState(0);
  const [busyId, setBusyId] = useState<string | null>(null);
  // Pre-fill for the next NEW task (a starter template or a duplicate); the form's key changes with it so it re-seeds.
  const [seed, setSeed] = useState<TaskSeed | null>(null);
  const [seedKey, setSeedKey] = useState(0);
  const [filter, setFilter] = useState<TaskFilter>("all");
  const [query, setQuery] = useState("");
  const reportedLoaded = useRef(false);
  // The host passes an inline callback: read it through a ref so a new identity never re-runs the load.
  const onLoadedRef = useRef(onLoaded);
  onLoadedRef.current = onLoaded;

  const reportLoaded = useCallback(() => {
    if (reportedLoaded.current) return;
    reportedLoaded.current = true;
    onLoadedRef.current?.();
  }, []);

  const init = useCallback(async () => {
    setFailed(false);
    try {
      const c = await api.getScheduledTasksConfig();
      if (c?.enabled) {
        const [list, agentList] = await Promise.all([
          api.listScheduledTasks(),
          Promise.resolve()
            .then(() => api.listAgents())
            .catch((): Awaited<ReturnType<typeof api.listAgents>> => []),
        ]);
        setTasks(Array.isArray(list) ? [...list].sort(byCreated) : []);
        setAgents(
          Array.isArray(agentList)
            ? agentList.map((a: any) => ({ id: String(a.id), name: String(a.name ?? a.id) }))
            : []
        );
      }
      // Set last: the page renders the New task button as soon as the config is in, and the form reads the agent
      // list when it opens, so everything it needs must already be there.
      setCfg(c);
    } catch (e: any) {
      // 404 = a core that predates the feature: the same "not enabled" state, not an error.
      if (e?.status === 404) setCfg(NOT_ENABLED);
      else setFailed(true);
    } finally {
      reportLoaded();
    }
  }, [reportLoaded]);

  useEffect(() => {
    void init();
  }, [init]);

  // A run finishes on the server, not in this tab: while any task's last run is still in flight, re-read the list
  // every few seconds and stop as soon as none is (prod pass bug 14 - the badge stayed "Running" after the run had
  // succeeded, until the next click). Each refresh also bumps runsKey so an open history shows the finished run.
  // Bounded on both sides: a failed poll stops it (no toast every few seconds during an outage), and a run that
  // never leaves "running" stops being polled after RUNNING_POLL_MAX_TICKS.
  const anyRunning = tasks.some((x) => isRunInFlight(x.lastStatus));
  useEffect(() => {
    if (!anyRunning) return undefined;
    let live = true;
    let ticks = 0;
    const id = window.setInterval(() => {
      if (++ticks > RUNNING_POLL_MAX_TICKS) {
        window.clearInterval(id);
        return;
      }
      api
        .listScheduledTasks()
        .then((fresh) => {
          if (!live || !Array.isArray(fresh)) return;
          setTasks([...fresh].sort(byCreated));
          setRunsKey((k) => k + 1);
        })
        .catch(() => window.clearInterval(id));
    }, RUNNING_POLL_MS);
    return () => {
      live = false;
      window.clearInterval(id);
    };
  }, [anyRunning]);

  const agentNames = useMemo(() => new Map(agents.map((a) => [a.id, a.name] as const)), [agents]);
  const counts = useMemo(() => taskStatusCounts(tasks), [tasks]);
  const [sort, setSort] = useState<TaskSort>(DEFAULT_TASK_SORT);
  const visibleTasks = useMemo(
    () => sortTasks(filterTasks(tasks, filter, query, agentNames), sort),
    [tasks, filter, query, agentNames, sort]
  );
  const taskPager = usePagination(visibleTasks, 10);

  const startNew = (next: TaskSeed | null) => {
    setSeed(next);
    setSeedKey((k) => k + 1);
    setEditing("new");
  };

  const duplicate = (task: ScheduledTask) =>
    startNew({
      name: t("copyName", { name: task.name }).slice(0, 120),
      prompt: task.prompt,
      agentId: task.agentId,
      schedule: draftFromTask(task),
      delivery: task.delivery,
      deliveryTo: task.deliveryTo ?? "",
    });

  const upsert = (task: ScheduledTask) =>
    setTasks((list) => [...list.filter((x) => x.id !== task.id), task].sort(byCreated));

  const act = async (task: ScheduledTask, run: () => Promise<void>) => {
    if (busyId) return;
    setBusyId(task.id);
    try {
      await run();
    } catch {
      /* the server's reason was already shown by the API layer's toast */
    } finally {
      setBusyId(null);
    }
  };

  const runNow = (task: ScheduledTask) =>
    act(task, async () => {
      const run = await api.runScheduledTaskNow(task.id);
      if (run?.status === "SKIPPED") toast.info(run.error || t("runNowSkipped"));
      else toast.success(t("runNowStarted"));
      // Reload the row (last status, run count) and show the history with the new run in it.
      const fresh = await api.listScheduledTasks();
      if (Array.isArray(fresh)) setTasks([...fresh].sort(byCreated));
      setOpenRuns(task.id);
      setRunsKey((k) => k + 1);
    });

  const pause = (task: ScheduledTask) =>
    act(task, async () => {
      upsert(await api.pauseScheduledTask(task.id));
      toast.success(t("paused"));
      setRunsKey((k) => k + 1);
    });

  const resume = (task: ScheduledTask) =>
    act(task, async () => {
      upsert(await api.resumeScheduledTask(task.id));
      toast.success(t("resumed"));
      setRunsKey((k) => k + 1);
    });

  const remove = (task: ScheduledTask) =>
    act(task, async () => {
      await api.deleteScheduledTask(task.id);
      setTasks((list) => list.filter((x) => x.id !== task.id));
      if (openRuns === task.id) setOpenRuns(null);
      toast.success(t("deleted"));
    });

  if (failed && !cfg) {
    return (
      <Alert variant="destructive">
        <AlertTriangle aria-hidden="true" />
        <AlertTitle>{t("loadFailed")}</AlertTitle>
        <AlertDescription>
          <Button variant="outline" size="sm" className="mt-2" onClick={() => void init()}>
            {t("retry")}
          </Button>
        </AlertDescription>
      </Alert>
    );
  }
  if (!cfg) return <ListSkeleton rows={3} label={t("loading")} />;

  if (!cfg.enabled) {
    return <EmptyState icon={CalendarClock} title={t("disabledTitle")} hint={t("disabledBody")} />;
  }

  const atLimit = cfg.maxTasks > 0 && tasks.length >= cfg.maxTasks;
  const starters = (
    <div data-testid="scheduled-starters" className="flex flex-col gap-2">
      <p className="text-xs font-medium text-muted-foreground">{t("startersTitle")}</p>
      <div className="flex flex-wrap gap-2">
        {SCHEDULED_TASK_TEMPLATES.map((tpl) => (
          <Button
            key={tpl.key}
            type="button"
            size="sm"
            variant="outline"
            disabled={atLimit}
            title={t(`tpl_${tpl.key}_hint`)}
            onClick={() =>
              startNew({
                name: tpl.name,
                prompt: tpl.prompt,
                schedule: { kind: tpl.kind, time: tpl.time, day: tpl.day ?? "MON" },
              })
            }
          >
            <Plus size={12} aria-hidden="true" /> {t(`tpl_${tpl.key}_title`)}
          </Button>
        ))}
      </div>
    </div>
  );

  return (
    <div className="flex flex-col gap-4">
      {failed && (
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("loadFailed")}</AlertTitle>
          <AlertDescription>
            <Button variant="outline" size="sm" className="mt-2" onClick={() => void init()}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      <ScheduledTasksSummary tasks={tasks} />
      <ScheduledTaskExplainer deliveryChannels={cfg.deliveryChannels} />

      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">
          {t("taskLimit", { count: tasks.length, max: cfg.maxTasks })}
        </span>
        <Button
          type="button"
          size="sm"
          disabled={atLimit && editing !== "new"}
          aria-expanded={editing === "new"}
          onClick={() => (editing === "new" ? setEditing(null) : startNew(null))}
        >
          <Plus size={14} aria-hidden="true" /> {t("newTask")}
        </Button>
      </div>
      {atLimit && <p className="text-xs text-muted-foreground">{t("limitReached", { max: cfg.maxTasks })}</p>}
      {editing === null && tasks.length > 0 && !atLimit && starters}

      {editing !== null && (
        <ScheduledTaskForm
          key={editing === "new" ? `new-${seedKey}` : editing.id}
          config={cfg}
          agents={agents}
          initial={editing === "new" ? null : editing}
          seed={editing === "new" ? seed : null}
          onCancel={() => setEditing(null)}
          onSaved={(task) => {
            upsert(task);
            setEditing(null);
          }}
        />
      )}

      {tasks.length === 0 ? (
        <>
          <EmptyState icon={CalendarClock} title={t("emptyTitle")} hint={t("emptyHint")} />
          {editing === null && starters}
        </>
      ) : (
        <>
          {tasks.length > 1 && (
            <div
              className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between"
              data-testid="scheduled-filter-bar"
            >
              <div className="relative w-full sm:max-w-xs">
                <Search
                  size={15}
                  className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                  aria-hidden="true"
                />
                <Input
                  type="search"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder={t("searchPlaceholder")}
                  aria-label={t("searchPlaceholder")}
                  className="ps-9"
                />
              </div>
              <div className="flex flex-wrap gap-2" role="group" aria-label={t("filterLabel")}>
                {(["all", "active", "failing", "paused"] as const).map((f) => (
                  <Button
                    key={f}
                    type="button"
                    size="sm"
                    variant={filter === f ? "default" : "outline"}
                    aria-pressed={filter === f}
                    onClick={() => setFilter(f)}
                  >
                    {t(`filter_${f}`, { count: f === "all" ? counts.total : counts[f] })}
                  </Button>
                ))}
              </div>
            </div>
          )}
          {visibleTasks.length === 0 && (
            <p
              role="status"
              className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground"
            >
              {t("noMatches")}
            </p>
          )}
          <ScheduledTasksTable
            tasks={taskPager.pageItems}
            agentNames={agentNames}
            atLimit={atLimit}
            maxTasks={cfg.maxTasks}
            busyId={busyId}
            openRuns={openRuns}
            runsKey={runsKey}
            sort={sort}
            onSortChange={setSort}
            onRunNow={(task) => void runNow(task)}
            onPause={(task) => void pause(task)}
            onResume={(task) => void resume(task)}
            onEdit={setEditing}
            onDuplicate={duplicate}
            onRemove={remove}
            onToggleRuns={setOpenRuns}
          />
          <PaginationControls
            page={taskPager.page}
            totalPages={taskPager.totalPages}
            totalItems={taskPager.totalItems}
            pageSize={taskPager.pageSize}
            onPageChange={taskPager.setPage}
            onPageSizeChange={taskPager.setPageSize}
            pageSizeOptions={[10, 25, 50]}
            itemLabel="tasks"
          />
        </>
      )}
    </div>
  );
}
