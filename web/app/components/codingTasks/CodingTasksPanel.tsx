"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AlertTriangle, FileDiff, Plus } from "lucide-react";
import Link from "next/link";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { isTerminalStatus, statusTone, type CodingTask, type CodingTasksConfig } from "../../lib/codingTasks";
import EmptyState from "../EmptyState";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import { Skeleton } from "../ui/skeleton";
import CodingTaskDetail from "./CodingTaskDetail";
import CodingTaskForm, { type AgentOption } from "./CodingTaskForm";

/**
 * /coding: start a coding task, follow it live, read the diff and approve / reject it, roll back.
 *
 * Rendering order follows what the server says: GET /api/coding-tasks/capabilities is always readable, so the page
 * can tell "not enabled in this deployment" (flag off) apart from a real load failure, and only calls the rest of
 * the API when the feature is usable. A live task is polled every few seconds; polling stops once it ends, and a
 * quiet 404 (the task expired) just stops it.
 */

export const POLL_MS = 2500;

const NOT_ENABLED: CodingTasksConfig = {
  enabled: false,
  allowed: false,
  reason: null,
  runner: "e2b",
  testRunners: [],
  defaults: { maxSteps: 20, maxWallSeconds: 900, maxTokens: 150000, maxFixIterations: 3 },
  caps: { maxSteps: 40, maxWallSeconds: 1800, maxTokens: 400000, maxFixIterations: 5 },
  maxArchiveBytes: 0,
  approvalTimeoutSeconds: 900,
  rollbackWindowSeconds: 600,
  networkEnabled: false,
};

function byCreated(a: CodingTask, b: CodingTask) {
  return b.createdAtMs - a.createdAtMs;
}

export default function CodingTasksPanel({ onLoaded }: { onLoaded?: () => void }) {
  const t = useTranslations("codingTasks");
  const [cfg, setCfg] = useState<CodingTasksConfig | null>(null);
  const [failed, setFailed] = useState(false);
  const [tasks, setTasks] = useState<CodingTask[]>([]);
  const [agents, setAgents] = useState<AgentOption[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [statusFilter, setStatusFilter] = useState<"all" | "live" | "done">("all");
  const tp = useTranslations("consolePolish");
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
      const c = await api.getCodingTasksConfig();
      if (c?.enabled) {
        const [list, agentList] = await Promise.all([
          api.listCodingTasks(),
          Promise.resolve()
            .then(() => api.listAgents())
            .catch((): Awaited<ReturnType<typeof api.listAgents>> => []),
        ]);
        const sorted: CodingTask[] = Array.isArray(list) ? [...list].sort(byCreated) : [];
        setTasks(sorted);
        setSelectedId((cur) => cur ?? sorted[0]?.taskId ?? null);
        setAgents(
          Array.isArray(agentList)
            ? agentList.map((a: any) => ({ id: String(a.id), name: String(a.name ?? a.id) }))
            : []
        );
      }
      // Set last: the page renders the New task button as soon as the config is in.
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

  const upsert = useCallback((task: CodingTask) => {
    setTasks((list) => [...list.filter((x) => x.taskId !== task.taskId), task].sort(byCreated));
  }, []);

  const filteredTasks = useMemo(
    () =>
      tasks.filter((x) =>
        statusFilter === "all"
          ? true
          : statusFilter === "live"
            ? !isTerminalStatus(x.status)
            : isTerminalStatus(x.status)
      ),
    [tasks, statusFilter]
  );
  const taskPager = usePagination(filteredTasks);

  const selected = tasks.find((x) => x.taskId === selectedId) ?? null;
  const selectedLive = selected !== null && !isTerminalStatus(selected.status);

  // Poll the selected task while it is live. The interval re-reads the id, not the task object, so a poll result
  // never restarts the timer.
  useEffect(() => {
    if (!selectedId || !selectedLive) return undefined;
    let live = true;
    const timer = setInterval(() => {
      api
        .getCodingTask(selectedId)
        .then((fresh: CodingTask) => {
          if (live && fresh) upsert(fresh);
        })
        .catch(() => {
          /* a blip or an expired task: the next tick (or a reload) sorts it out */
        });
    }, POLL_MS);
    return () => {
      live = false;
      clearInterval(timer);
    };
  }, [selectedId, selectedLive, upsert]);

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
  if (!cfg)
    return (
      <div className="space-y-3" aria-busy="true" aria-label={t("loading")}>
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-32 w-full" />
      </div>
    );

  if (!cfg.enabled) {
    return <EmptyState icon={FileDiff} title={t("disabledTitle")} hint={t("disabledBody")} />;
  }

  return (
    <div className="flex flex-col gap-4">
      {!cfg.allowed && (
        <Alert variant="warning" data-testid="coding-not-allowed">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>
            {cfg.reason === "PLAN" ? t("planBody") : t("setupBody")}{" "}
            <Link href={cfg.reason === "PLAN" ? "/admin/billing" : "/providers"} className="font-semibold underline">
              {t(cfg.reason === "PLAN" ? "upgradeLink" : "providersLink")}
            </Link>
          </AlertDescription>
        </Alert>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{t("safetyNote")}</span>
        <Button
          type="button"
          size="sm"
          disabled={!cfg.allowed}
          aria-expanded={creating}
          onClick={() => setCreating((c) => !c)}
        >
          <Plus size={14} aria-hidden="true" /> {t("newTask")}
        </Button>
      </div>

      {creating && (
        <CodingTaskForm
          config={cfg}
          agents={agents}
          onCancel={() => setCreating(false)}
          onStarted={(task) => {
            upsert(task);
            setSelectedId(task.taskId);
            setCreating(false);
          }}
        />
      )}

      {tasks.length === 0 ? (
        <EmptyState icon={FileDiff} title={t("emptyTitle")} hint={t("emptyHint")} />
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-[16rem_minmax(0,1fr)] lg:grid-cols-[18rem_minmax(0,1fr)]">
          <div className="flex min-w-0 flex-col gap-2">
            <div role="group" aria-label={tp("codingStatusFilter")} className="flex flex-wrap gap-2">
              {(["all", "live", "done"] as const).map((f) => (
                <Button
                  key={f}
                  type="button"
                  size="sm"
                  variant={statusFilter === f ? "default" : "outline"}
                  className="rounded-full"
                  aria-pressed={statusFilter === f}
                  onClick={() => setStatusFilter(f)}
                >
                  {tp(f === "all" ? "codingFilterAll" : f === "live" ? "codingFilterLive" : "codingFilterDone")}
                </Button>
              ))}
            </div>
            {filteredTasks.length === 0 && (
              <p role="status" className="text-sm text-muted-foreground">
                {tp("codingNoMatch")}
              </p>
            )}
            <ul className="flex flex-col gap-2" data-testid="coding-task-list" aria-label={t("listTitle")}>
              {taskPager.pageItems.map((task) => (
                <li key={task.taskId}>
                  <button
                    type="button"
                    aria-pressed={task.taskId === selectedId}
                    onClick={() => setSelectedId(task.taskId)}
                    className={`flex w-full flex-col gap-1 rounded-md border px-3 py-2 text-left text-sm transition-colors ${
                      task.taskId === selectedId ? "border-ring bg-secondary" : "border-border hover:bg-secondary/50"
                    }`}
                  >
                    <span className="line-clamp-2 text-foreground">{task.instruction}</span>
                    <Badge variant="state" state={`var(--state-${statusTone(task.status)})`}>
                      {t(`status_${task.status}`)}
                    </Badge>
                  </button>
                </li>
              ))}
            </ul>
            <PaginationControls
              page={taskPager.page}
              totalPages={taskPager.totalPages}
              totalItems={taskPager.totalItems}
              pageSize={taskPager.pageSize}
              onPageChange={taskPager.setPage}
              onPageSizeChange={taskPager.setPageSize}
              itemLabel={tp("codingItemLabel")}
            />
          </div>
          {selected && <CodingTaskDetail key={selected.taskId} task={selected} onChange={upsert} />}
        </div>
      )}
    </div>
  );
}
