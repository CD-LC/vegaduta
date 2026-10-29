"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { RefreshCw, ArrowDownToLine, ArrowUpFromLine, ExternalLink } from "lucide-react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState } from "../../components/ax/page-kit";
import { ErrorRetry, FilterPills, ListSkeleton, PagedList } from "../commerce/listKit";
import { usePageReady } from "../../components/RouteTransition";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Card } from "../../components/ui/card";
import { Select } from "../../components/ui/select";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { api, API_BASE } from "../../lib/api";
import type { A2aConsolePolicy, A2aDirection, A2aTaskRow } from "../../lib/api/a2a";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { fmtTime } from "../agent-trust/trustFormat";

// A2A interoperability console (core/agents-interop a2a/A2aConsoleController).
// Three jobs:
//   1. Show the tenant the INBOUND endpoints a peer agent calls (JSON-RPC +
//      REST bindings, agent card, JWKS) - the URL material you hand an
//      integration partner.
//   2. Show which REMOTE agents this tenant's agents may call via the
//      a2a_call_agent tool (operator allow-list, read-only on purpose - the
//      egress surface is an environment setting, see OutboundPolicyController).
//   3. The task console: every A2A task this deployment still holds for the
//      tenant, both directions, with detail and cancel. The protocol binding
//      answers ListTasks unsupported on purpose (A2aRestController); this is
//      the admin view the spec leaves to the platform.
// Scope gate MANAGE_AGENTS is UX only - the controller's @PreAuthorize is the
// real check. On an older core or a personal install (no A2A_DIRECTORY) every
// call 404s and the page renders the feature-off notice instead of errors.

const STATE_ORDER = [
  "TASK_STATE_SUBMITTED",
  "TASK_STATE_WORKING",
  "TASK_STATE_COMPLETED",
  "TASK_STATE_FAILED",
  "TASK_STATE_CANCELED",
];

export default function AdminA2aPage() {
  return (
    <Suspense fallback={null}>
      <AdminA2aPageInner />
    </Suspense>
  );
}

function AdminA2aPageInner() {
  const allowed = useRequireScope("MANAGE_AGENTS");
  const t = useTranslations("adminA2aPage");
  const tu = useTranslations("adminCommerceUi");
  const searchParams = useSearchParams();

  const [agents, setAgents] = useState<any[]>([]);
  const [policy, setPolicy] = useState<A2aConsolePolicy | null>(null);
  const [policyMissing, setPolicyMissing] = useState(false);
  const [tasks, setTasks] = useState<A2aTaskRow[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [direction, setDirection] = useState<A2aDirection | "">("");
  const [state, setState] = useState("");
  const [agentId, setAgentId] = useState(searchParams.get("agent") ?? "");
  const [selected, setSelected] = useState<A2aTaskRow | null>(null);
  const [cancelBusy, setCancelBusy] = useState<string | null>(null);

  usePageReady(loaded);

  const agentNames = useMemo(() => {
    const names = new Map<string, string>();
    for (const a of agents) names.set(String(a.id), a.name || String(a.id));
    return names;
  }, [agents]);

  const loadTasks = useCallback(
    async (fresh: boolean) => {
      try {
        const res = await api.a2aListTasks(
          {
            direction: direction || undefined,
            state: state || undefined,
            agentId: agentId || undefined,
            limit: 200,
          },
          fresh
        );
        setTasks(res?.tasks ?? []);
        setError(null);
      } catch (e: any) {
        setTasks([]);
        setError(errorMessage(e) || t("tasksLoadFailed"));
      }
    },
    [direction, state, agentId, t]
  );

  useEffect(() => {
    if (!allowed) return;
    void Promise.all([
      api
        .listAgents()
        .then((rows: any) => setAgents(Array.isArray(rows) ? rows : []))
        .catch(() => setAgents([])),
      api
        .a2aPolicy()
        .then((p: A2aConsolePolicy | null) => setPolicy(p))
        .catch(() => setPolicyMissing(true)),
      loadTasks(false),
    ]).finally(() => setLoaded(true));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowed]);

  // Filter changes re-ask the server (direction/state/agentId are query params).
  useEffect(() => {
    if (!allowed || !loaded) return;
    void loadTasks(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [direction, state, agentId]);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      await loadTasks(true);
    } finally {
      setRefreshing(false);
    }
  }, [loadTasks]);

  const cancelTask = useCallback(
    async (task: A2aTaskRow) => {
      if (cancelBusy) return;
      setCancelBusy(task.id);
      try {
        const updated = await api.a2aCancelTask(task.id);
        toast.success(t("cancelSuccess"));
        setTasks((rows) => rows.map((r) => (r.id === task.id ? { ...r, ...updated } : r)));
        setSelected((s) => (s && s.id === task.id ? { ...s, ...updated } : s));
      } catch (e: any) {
        toast.error(errorMessage(e) || t("cancelFailed"));
      } finally {
        setCancelBusy(null);
      }
    },
    [cancelBusy, t]
  );

  if (!allowed) return null;

  const agentOptions = agents.map((a: any) => ({ value: String(a.id), label: a.name || String(a.id) }));

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <>
        <PageHeader title={t("pageTitle")} subtitle={t("pageSubtitle")} />

        {policyMissing && (
          <p className="mb-4 text-sm text-muted-foreground" role="note">
            {t("featureOff")}
          </p>
        )}

        {policy && (
          <div className="mb-6 grid gap-4 md:grid-cols-2">
            <Card className="p-4">
              <h2 className="mb-3 text-sm font-semibold">{t("inboundHeading")}</h2>
              <dl className="grid gap-2 text-xs">
                <EndpointRow label={t("jsonRpc")} path={policy.server.jsonRpcPath} method="POST" />
                <EndpointRow label={t("restBinding")} path={policy.server.restPath} method="POST" />
                <EndpointRow
                  label={t("agentCard")}
                  path={policy.server.agentCardPath}
                  method="GET"
                />
                <EndpointRow label={t("platformCard")} path={policy.server.platformCardPath} method="GET" />
                <EndpointRow label={t("jwks")} path={policy.server.jwksPath} method="GET" />
              </dl>
              <p className="mt-3 text-xs text-muted-foreground">{t("inboundHint")}</p>
            </Card>

            <Card className="p-4">
              <div className="mb-3 flex items-center justify-between">
                <h2 className="text-sm font-semibold">{t("outboundHeading")}</h2>
                <Badge variant={policy.client.enabled ? "default" : "secondary"}>
                  {policy.client.enabled ? t("on") : t("off")}
                </Badge>
              </div>
              {policy.client.enabled ? (
                <>
                  <p className="mb-2 text-xs font-medium text-muted-foreground">
                    {t("allowedHosts")}
                  </p>
                  <div className="flex flex-wrap gap-1.5">
                    {policy.client.allowedHosts.length === 0 ? (
                      <span className="text-xs text-muted-foreground">{t("noAllowedHosts")}</span>
                    ) : (
                      policy.client.allowedHosts.map((h) => (
                        <Badge key={h} variant="outline" className="font-mono">
                          {h}
                        </Badge>
                      ))
                    )}
                  </div>
                  <dl className="mt-3 grid gap-1.5 text-xs">
                    <div className="flex justify-between gap-2">
                      <dt className="text-muted-foreground">{t("signedCards")}</dt>
                      <dd>{policy.client.requireSignedCard ? t("yes") : t("no")}</dd>
                    </div>
                    <div className="flex justify-between gap-2">
                      <dt className="text-muted-foreground">{t("maxCallsPerTurn")}</dt>
                      <dd>{policy.client.maxCallsPerTurn > 0 ? policy.client.maxCallsPerTurn : t("noCap")}</dd>
                    </div>
                    <div className="flex justify-between gap-2">
                      <dt className="text-muted-foreground">{t("maxWait")}</dt>
                      <dd>{t("maxWaitValue", { seconds: policy.client.maxWaitSeconds })}</dd>
                    </div>
                  </dl>
                </>
              ) : (
                <p className="text-xs text-muted-foreground">{t("outboundOff")}</p>
              )}
              <p className="mt-3 text-xs text-muted-foreground">{t("outboundHint")}</p>
            </Card>
          </div>
        )}

        <p className="mb-4 text-sm text-muted-foreground">
          {tu("relatedLabel")}:{" "}
          <Link href="/admin/agent-trust" className="text-primary underline">
            {t("relatedAgentTrust")}
          </Link>
          {" · "}
          <Link href="/directory" className="text-primary underline" target="_blank">
            {t("relatedDirectory")}
            <ExternalLink className="ml-1 inline size-3" aria-hidden="true" />
          </Link>
        </p>

        <Card className="p-4">
          <div className="mb-3 flex flex-wrap items-center gap-3">
            <h2 className="text-sm font-semibold">{t("tasksHeading")}</h2>
            <div className="ml-auto flex items-center gap-2">
              <div className="w-52">
                <Select
                  id="a2a-agent-filter"
                  value={agentId}
                  onValueChange={setAgentId}
                  placeholder={t("allAgents")}
                  options={[{ value: "", label: t("allAgents") }, ...agentOptions]}
                />
              </div>
              <Button type="button" variant="outline" size="sm" onClick={() => void refresh()} disabled={refreshing}>
                <RefreshCw className={`size-3.5 ${refreshing ? "animate-spin" : ""}`} aria-hidden="true" />
                {t("refresh")}
              </Button>
            </div>
          </div>

          <div className="mb-3 flex flex-wrap items-center gap-x-6 gap-y-2">
            <FilterPills
              options={["inbound", "outbound"]}
              value={direction}
              onChange={(v) => setDirection(v as A2aDirection | "")}
            />
            <FilterPills options={STATE_ORDER} value={state} onChange={setState} />
          </div>

          {error && <ErrorRetry message={error} onRetry={() => void refresh()} />}

          {!loaded && <ListSkeleton />}

          {loaded && !error && tasks.length === 0 && (
            <EmptyState title={t("noTasksTitle")} description={t("noTasksHint")} />
          )}

          {loaded && tasks.length > 0 && (
            <PagedList
              items={tasks}
              itemLabel={tu("itemTasks")}
              className="grid gap-2"
              capped={200}
              renderItem={(row) => (
                <TaskRow
                  key={row.id}
                  row={row}
                  agentName={agentNames.get(row.agentId)}
                  onOpen={() => setSelected(row)}
                  onCancel={() => void cancelTask(row)}
                  cancelBusy={cancelBusy === row.id}
                />
              )}
            />
          )}
        </Card>

        <TaskDetailDialog
          task={selected}
          agentName={selected ? agentNames.get(selected.agentId) : undefined}
          onClose={() => setSelected(null)}
          onCancel={selected && !selected.terminal ? () => void cancelTask(selected) : null}
          cancelBusy={!!selected && cancelBusy === selected.id}
        />
      </>
    </AppShell>
  );
}

function EndpointRow({ label, path, method }: { label: string; path: string; method: string }) {
  return (
    <div className="flex items-baseline justify-between gap-2">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="truncate text-right font-mono text-foreground" title={`${API_BASE}${path}`}>
        <span className="mr-1.5 text-muted-foreground">{method}</span>
        {path}
      </dd>
    </div>
  );
}

function stateVariant(state: string): "default" | "secondary" | "destructive" | "outline" {
  switch (state) {
    case "TASK_STATE_COMPLETED":
      return "default";
    case "TASK_STATE_FAILED":
      return "destructive";
    case "TASK_STATE_CANCELED":
      return "outline";
    default:
      return "secondary";
  }
}

function TaskRow({
  row,
  agentName,
  onOpen,
  onCancel,
  cancelBusy,
}: {
  row: A2aTaskRow;
  agentName?: string;
  onOpen: () => void;
  onCancel: () => void;
  cancelBusy: boolean;
}) {
  const t = useTranslations("adminA2aPage");
  const DirectionIcon = row.direction === "inbound" ? ArrowDownToLine : ArrowUpFromLine;
  return (
    <div className="rounded-lg border border-border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={row.direction === "inbound" ? "default" : "secondary"}>
          <DirectionIcon className="mr-1 size-3" aria-hidden="true" />
          {t(row.direction === "inbound" ? "dirInbound" : "dirOutbound")}
        </Badge>
        <Badge variant={stateVariant(row.state)}>{row.state.replace("TASK_STATE_", "")}</Badge>
        <span className="font-medium text-sm text-foreground">
          {agentName || (row.direction === "outbound" ? t("remoteAgent") : row.agentId)}
        </span>
        <span className="font-mono text-xs text-muted-foreground" title={row.contextId}>
          {row.contextId.slice(0, 8)}…
        </span>
        {row.subject && <span className="text-xs text-muted-foreground">{row.subject}</span>}
        <span className="ml-auto text-xs text-muted-foreground">{fmtTime(row.createdAt)}</span>
        <Button type="button" variant="outline" size="sm" onClick={onOpen}>
          {t("view")}
        </Button>
        {!row.terminal && row.direction === "inbound" && (
          <Button
            type="button"
            variant="destructive"
            size="sm"
            disabled={cancelBusy}
            onClick={onCancel}
          >
            {cancelBusy ? t("canceling") : t("cancel")}
          </Button>
        )}
      </div>
      <p className="mt-2 line-clamp-2 text-xs text-muted-foreground">{row.userText}</p>
      {row.failureMessage && (
        <p className="mt-1 text-xs text-destructive">{row.failureMessage}</p>
      )}
    </div>
  );
}

function TaskDetailDialog({
  task,
  agentName,
  onClose,
  onCancel,
  cancelBusy,
}: {
  task: A2aTaskRow | null;
  agentName?: string;
  onClose: () => void;
  onCancel: (() => void) | null;
  cancelBusy: boolean;
}) {
  const t = useTranslations("adminA2aPage");
  return (
    <AlertDialog open={!!task} onOpenChange={(v) => !v && onClose()}>
      <AlertDialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <AlertDialogHeader>
          <AlertDialogTitle>{t("detailTitle")}</AlertDialogTitle>
          <AlertDialogDescription>{t("detailSubtitle")}</AlertDialogDescription>
        </AlertDialogHeader>
        {task && (
          <div className="grid gap-3 text-xs">
            <dl className="grid gap-1.5">
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">{t("detailAgent")}</dt>
                <dd className="text-foreground">{agentName || task.agentId}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">{t("detailContext")}</dt>
                <dd className="font-mono text-foreground">{task.contextId}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">{t("detailSubject")}</dt>
                <dd className="text-foreground">{task.subject || "—"}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">{t("detailCreated")}</dt>
                <dd className="text-foreground">{fmtTime(task.createdAt)}</dd>
              </div>
              <div className="flex justify-between gap-2">
                <dt className="text-muted-foreground">{t("detailUpdated")}</dt>
                <dd className="text-foreground">{fmtTime(task.stateTimestamp)}</dd>
              </div>
            </dl>
            <div>
              <p className="mb-1 font-medium text-foreground">{t("detailRequest")}</p>
              <pre className="max-h-40 overflow-auto whitespace-pre-wrap rounded bg-muted p-2 text-foreground">
                {task.userText || "—"}
              </pre>
            </div>
            <div>
              <p className="mb-1 font-medium text-foreground">{t("detailReply")}</p>
              <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded bg-muted p-2 text-foreground">
                {task.reply || "—"}
              </pre>
            </div>
            {task.failureMessage && (
              <p className="text-destructive">{task.failureMessage}</p>
            )}
          </div>
        )}
        <AlertDialogFooter>
          {onCancel && task?.direction === "inbound" && (
            <Button type="button" variant="destructive" size="sm" disabled={cancelBusy} onClick={onCancel}>
              {cancelBusy ? t("canceling") : t("cancel")}
            </Button>
          )}
          <AlertDialogCancel>{t("close")}</AlertDialogCancel>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
