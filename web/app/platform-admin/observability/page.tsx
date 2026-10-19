"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { Radar, RefreshCw, Search, X, BarChart3 } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard } from "../components/sections/common";
import { ExternalToolsSection } from "../components/sections/external-tools";
import { MiniBars, ToneChip, UnavailableNote } from "../components/ops/OpsWidgets";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import {
  tracesApi,
  type TraceDetailResponse,
  type TraceFilters,
  type TraceListResponse,
  type TraceMetricsResponse,
} from "../lib/adminApi/traces";
import PaginationControls from "../components/PaginationControls";
import { pageForSizeChange } from "../lib/usePagination";
import { useDialogFocus } from "../lib/useDialogFocus";
import {
  UNAVAILABLE,
  formatCountOrUnavailable,
  formatExact,
  formatRelative,
  formatUsdOrUnavailable,
} from "../lib/platformFormat";
import {
  formatCompact,
  formatDurationMs,
  isoToLocalInput,
  lastDaysRange,
  localInputToIso,
  sumMetrics,
} from "../lib/opsFormat";

// LLM observability: traces from Langfuse, proxied by core
// (GET /api/platform/traces[/{id}|/metrics]) so no Langfuse key or origin
// reaches the browser. When core reports available:false (Langfuse not
// configured for this environment, or unreachable) the panels show that
// reason - it is a state, not an error page. Charts are plain SVG, same as
// the rest of this console.

const RANGES = [
  { days: 1, label: "24 h" },
  { days: 7, label: "7 days" },
  { days: 30, label: "30 days" },
];

function levelTone(level: string | null | undefined) {
  if (level === "ERROR") return "bad" as const;
  if (level === "WARNING") return "warn" as const;
  return "muted" as const;
}

function TraceDrawer({ id, onClose }: { id: string | null; onClose: () => void }) {
  const [detail, setDetail] = useState<TraceDetailResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [mounted, setMounted] = useState(false);
  const ref = useRef<HTMLDivElement | null>(null);
  useDialogFocus(!!id, ref);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (!id) return undefined;
    let cancelled = false;
    setDetail(null);
    setError(null);
    tracesApi
      .get(id)
      .then((d) => !cancelled && setDetail(d))
      .catch((e) => !cancelled && setError(e?.message || "Could not load this trace."));
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => {
      cancelled = true;
      window.removeEventListener("keydown", onKey);
    };
  }, [id, onClose]);

  if (!mounted) return null;
  const t = detail?.trace;

  return createPortal(
    <AnimatePresence>
      {id && (
        <motion.div
          className="fixed inset-0 z-[90] flex justify-end bg-black/50 backdrop-blur-sm"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          onClick={onClose}
        >
          <motion.div
            ref={ref}
            role="dialog"
            aria-modal="true"
            aria-labelledby="trace-drawer-title"
            tabIndex={-1}
            className="h-full w-full max-w-2xl overflow-y-auto border-l border-border bg-card p-5 shadow-xl outline-none"
            initial={{ x: 40, opacity: 0 }}
            animate={{ x: 0, opacity: 1 }}
            exit={{ x: 40, opacity: 0 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-4 flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h2 id="trace-drawer-title" className="truncate text-sm font-semibold">
                  {t?.name || "Trace"}
                </h2>
                <p className="break-all font-mono text-[11px] text-muted-foreground">{id}</p>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="Close trace"
                className="rounded-md border-0 bg-transparent p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X size={16} />
              </button>
            </div>

            {error && (
              <Alert variant="destructive">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            {!detail && !error && <Skeleton className="h-64 w-full" />}
            {detail && !detail.available && (
              <UnavailableNote title="Langfuse is not available" reason={detail.reason} />
            )}
            {detail?.available && t && (
              <div className="space-y-4 text-sm">
                <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-xs">
                  <dt className="text-muted-foreground">When</dt>
                  <dd title={formatExact(t.timestamp)}>{formatExact(t.timestamp)}</dd>
                  <dt className="text-muted-foreground">Tenant</dt>
                  <dd className="break-all font-mono">{t.tenantId || UNAVAILABLE}</dd>
                  <dt className="text-muted-foreground">User</dt>
                  <dd className="break-all font-mono">{t.userId || UNAVAILABLE}</dd>
                  <dt className="text-muted-foreground">Session</dt>
                  <dd className="break-all font-mono">{t.sessionId || UNAVAILABLE}</dd>
                  <dt className="text-muted-foreground">Latency</dt>
                  <dd>{formatDurationMs(t.latencyMs)}</dd>
                  <dt className="text-muted-foreground">Cost</dt>
                  <dd>{formatUsdOrUnavailable(t.totalCostUsd)}</dd>
                  <dt className="text-muted-foreground">Tokens in / out</dt>
                  <dd>
                    {formatCountOrUnavailable(t.inputTokens)} / {formatCountOrUnavailable(t.outputTokens)}
                  </dd>
                </dl>
                {t.tags && t.tags.length > 0 && (
                  <div className="flex flex-wrap gap-1">
                    {t.tags.map((tag) => (
                      <span key={tag} className="rounded border border-border px-1.5 py-0.5 font-mono text-[11px]">
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                    Input (truncated)
                  </p>
                  <pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-md bg-secondary/40 p-2 font-mono text-[11px]">
                    {t.input || "—"}
                  </pre>
                </div>
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                    Output (truncated)
                  </p>
                  <pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-md bg-secondary/40 p-2 font-mono text-[11px]">
                    {t.output || "—"}
                  </pre>
                </div>
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                    Observations ({detail.observations?.length ?? 0})
                  </p>
                  <div className="mt-1 space-y-1.5">
                    {(detail.observations || []).map((o) => {
                      const dur =
                        o.startTime && o.endTime
                          ? new Date(o.endTime).getTime() - new Date(o.startTime).getTime()
                          : null;
                      return (
                        <div key={o.id} className="rounded-md border border-border p-2 text-xs">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-mono text-[11px] text-muted-foreground">{o.type || "?"}</span>
                            <span className="font-medium">{o.name || o.id}</span>
                            {o.model && <span className="font-mono text-[11px]">{o.model}</span>}
                            {o.level && o.level !== "DEFAULT" && <ToneChip tone={levelTone(o.level)} label={o.level} />}
                          </div>
                          <div className="mt-1 text-[11px] text-muted-foreground">
                            {formatDurationMs(dur)} · {formatCountOrUnavailable(o.inputTokens)} in /{" "}
                            {formatCountOrUnavailable(o.outputTokens)} out · {formatUsdOrUnavailable(o.costUsd)}
                          </div>
                          {o.statusMessage && (
                            <div className="mt-1 text-[11px] text-destructive">{o.statusMessage}</div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                </div>
              </div>
            )}
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}

function MetricsSection({ range }: { range: { from: string; to: string } }) {
  const [data, setData] = useState<TraceMetricsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setData(null);
    setError(null);
    tracesApi
      .metrics(range)
      .then((d) => !cancelled && setData(d))
      .catch((e) => !cancelled && setError(e?.message || "Could not load trace metrics."));
    return () => {
      cancelled = true;
    };
  }, [range]);

  const days = data?.byDay || [];
  const labels = days.map((d) => d.date?.slice(5) || "");
  const totals = sumMetrics(days);

  return (
    <SectionCard
      title="LLM metrics"
      icon={BarChart3}
      subtitle="Daily traces, cost and latency from Langfuse, plus a per-model breakdown."
      delay={0.03}
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {!data && !error && <Skeleton className="h-40 w-full" />}
      {data && !data.available && (
        <UnavailableNote
          title="Langfuse is not available"
          reason={data.reason || "Langfuse not configured or unreachable."}
        />
      )}
      {data?.available && (
        <div className="space-y-5">
          <div className="flex flex-wrap gap-4 text-xs text-muted-foreground">
            <span>
              Traces in window:{" "}
              <span className="font-mono text-foreground">{formatCountOrUnavailable(totals.traces)}</span>
            </span>
            <span>
              Cost in window:{" "}
              <span className="font-mono text-foreground">{formatUsdOrUnavailable(totals.costUsd)}</span>
            </span>
          </div>
          <div className="grid gap-5 md:grid-cols-3">
            <MiniBars
              title="Traces / day"
              labels={labels}
              values={days.map((d) => d.traces)}
              fmt={formatCompact}
              testId="chart-traces"
            />
            <MiniBars
              title="Cost / day (USD)"
              labels={labels}
              values={days.map((d) => d.costUsd)}
              fmt={formatUsdOrUnavailable}
              testId="chart-cost"
            />
            <MiniBars
              title="p95 latency / day"
              labels={labels}
              values={days.map((d) => d.p95LatencyMs)}
              fmt={formatDurationMs}
              testId="chart-p95"
            />
          </div>
          {data.byModel?.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[560px] text-left text-sm">
                <thead className="text-xs text-muted-foreground">
                  <tr className="border-b border-border">
                    <th className="py-2 pr-3 font-medium">Model</th>
                    <th className="py-2 pr-3 font-medium">Calls</th>
                    <th className="py-2 pr-3 font-medium">Tokens in</th>
                    <th className="py-2 pr-3 font-medium">Tokens out</th>
                    <th className="py-2 font-medium">Cost</th>
                  </tr>
                </thead>
                <tbody>
                  {[...data.byModel]
                    .sort((a, b) => (b.costUsd ?? -1) - (a.costUsd ?? -1))
                    .map((m) => (
                      <tr key={m.model} className="border-b border-border/60">
                        <td className="py-2 pr-3 font-mono text-xs">{m.model}</td>
                        <td className="py-2 pr-3 font-mono text-xs">{formatCountOrUnavailable(m.calls)}</td>
                        <td className="py-2 pr-3 font-mono text-xs">{formatCompact(m.inputTokens)}</td>
                        <td className="py-2 pr-3 font-mono text-xs">{formatCompact(m.outputTokens)}</td>
                        <td className="py-2 font-mono text-xs">{formatUsdOrUnavailable(m.costUsd)}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </SectionCard>
  );
}

export default function ObservabilityPage() {
  const [days, setDays] = useState(7);
  // Bumped by Refresh so a preset window ("last 7 days") slides forward to now.
  const [tick, setTick] = useState(0);
  const [fromLocal, setFromLocal] = useState("");
  const [toLocal, setToLocal] = useState("");
  const [tenantId, setTenantId] = useState("");
  const [userId, setUserId] = useState("");
  const [name, setName] = useState("");
  const [applied, setApplied] = useState<TraceFilters>({});
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [list, setList] = useState<TraceListResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [openId, setOpenId] = useState<string | null>(null);

  // Custom from/to override the preset window when either is set.
  const range = useMemo(() => {
    const preset = lastDaysRange(days);
    const from = localInputToIso(fromLocal);
    const to = localInputToIso(toLocal);
    return { from: from || preset.from, to: to || preset.to };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [days, fromLocal, toLocal, tick]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await tracesApi.list({ ...range, ...applied, page, limit: pageSize }));
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not load traces.");
    } finally {
      setLoading(false);
    }
  }, [range, applied, page, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  const closeDrawer = useCallback(() => setOpenId(null), []);
  const rows = list?.data || [];
  const meta = list?.meta;

  return (
    <AdminShell
      title="Observability"
      actions={
        <Button variant="outline" size="sm" onClick={() => setTick((n) => n + 1)} disabled={loading}>
          <RefreshCw className={loading ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Every LLM trace across tenants, read from Langfuse through core. Filter by tenant, user or trace name, open a
        trace for its spans, and see daily cost and latency. External dashboards are linked at the bottom.
      </PageHint>

      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div
          className="flex items-center gap-1 rounded-md border border-border p-0.5"
          role="group"
          aria-label="Time window"
        >
          {RANGES.map((r) => (
            <button
              key={r.days}
              type="button"
              aria-pressed={days === r.days && !fromLocal && !toLocal}
              onClick={() => {
                setFromLocal("");
                setToLocal("");
                setDays(r.days);
                setPage(1);
              }}
              className={`rounded px-2.5 py-1 text-xs transition-colors ${
                days === r.days && !fromLocal && !toLocal
                  ? "bg-primary/12 font-medium text-primary"
                  : "text-muted-foreground hover:bg-accent"
              }`}
            >
              {r.label}
            </button>
          ))}
        </div>
        <div className="space-y-1">
          <Label htmlFor="obs-from" className="text-[11px] text-muted-foreground">
            From
          </Label>
          <Input
            id="obs-from"
            type="datetime-local"
            className="h-8 w-52"
            value={fromLocal}
            onChange={(e) => {
              setFromLocal(e.target.value);
              setPage(1);
            }}
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="obs-to" className="text-[11px] text-muted-foreground">
            To
          </Label>
          <Input
            id="obs-to"
            type="datetime-local"
            className="h-8 w-52"
            value={toLocal}
            onChange={(e) => {
              setToLocal(e.target.value);
              setPage(1);
            }}
          />
        </div>
        <span className="pb-1.5 text-[11px] text-muted-foreground">
          {isoToLocalInput(range.from).replace("T", " ")} → {isoToLocalInput(range.to).replace("T", " ")}
        </span>
      </div>

      <MetricsSection range={range} />

      <SectionCard
        title="Traces"
        icon={Radar}
        subtitle={
          meta && list?.available ? `${formatCountOrUnavailable(meta.totalItems)} trace(s) in window` : undefined
        }
        delay={0.06}
        actions={
          <form
            className="flex flex-wrap items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              setPage(1);
              setApplied({
                tenantId: tenantId.trim() || undefined,
                userId: userId.trim() || undefined,
                name: name.trim() || undefined,
              });
            }}
          >
            <Input
              aria-label="Tenant id"
              className="h-9 w-40"
              placeholder="Tenant id"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
            />
            <Input
              aria-label="User id"
              className="h-9 w-40"
              placeholder="User id"
              value={userId}
              onChange={(e) => setUserId(e.target.value)}
            />
            <Input
              aria-label="Trace name"
              className="h-9 w-40"
              placeholder="Trace name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <Button type="submit" size="sm">
              <Search /> Filter
            </Button>
          </form>
        }
      >
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        {loading && !list && <Skeleton className="h-40 w-full" />}
        {list && !list.available && (
          <UnavailableNote
            title="Langfuse is not available"
            reason={list.reason || "Langfuse not configured or unreachable."}
          />
        )}
        {list?.available && rows.length === 0 && (
          <p className="text-sm text-muted-foreground">No traces match this window and filter.</p>
        )}
        {list?.available && rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr className="border-b border-border">
                  <th className="py-2 pr-3 font-medium">Trace</th>
                  <th className="py-2 pr-3 font-medium">When</th>
                  <th className="py-2 pr-3 font-medium">Tenant / user</th>
                  <th className="py-2 pr-3 font-medium">Latency</th>
                  <th className="py-2 pr-3 font-medium">Tokens</th>
                  <th className="py-2 font-medium">Cost</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((t) => (
                  <tr
                    key={t.id}
                    className="cursor-pointer border-b border-border/60 hover:bg-accent/50"
                    onClick={() => setOpenId(t.id)}
                    data-testid="trace-row"
                  >
                    <td className="py-2 pr-3">
                      <button
                        type="button"
                        className="border-0 bg-transparent p-0 text-left font-medium text-foreground hover:underline"
                        onClick={(e) => {
                          e.stopPropagation();
                          setOpenId(t.id);
                        }}
                      >
                        {t.name || t.id.slice(0, 12)}
                      </button>
                      {t.level && t.level !== "DEFAULT" && (
                        <span className="ml-2">
                          <ToneChip tone={levelTone(t.level)} label={t.level} />
                        </span>
                      )}
                    </td>
                    <td className="py-2 pr-3 text-xs" title={formatExact(t.timestamp)}>
                      {formatRelative(t.timestamp)}
                    </td>
                    <td className="py-2 pr-3 font-mono text-[11px] text-muted-foreground">
                      {(t.tenantId || "—").slice(0, 8)} / {(t.userId || "—").slice(0, 12)}
                    </td>
                    <td className="py-2 pr-3 font-mono text-xs">{formatDurationMs(t.latencyMs)}</td>
                    <td className="py-2 pr-3 font-mono text-xs">
                      {formatCompact(t.inputTokens)} / {formatCompact(t.outputTokens)}
                    </td>
                    <td className="py-2 font-mono text-xs">{formatUsdOrUnavailable(t.totalCostUsd)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {list?.available && meta && (
          <PaginationControls
            page={meta.page}
            pageSize={pageSize}
            totalPages={meta.totalPages}
            totalItems={meta.totalItems}
            loading={loading}
            itemLabel="traces"
            onPageChange={setPage}
            onPageSizeChange={(n) => {
              setPage(pageForSizeChange(page, pageSize, n));
              setPageSize(n);
            }}
          />
        )}
      </SectionCard>

      <ExternalToolsSection delay={0.09} />

      <TraceDrawer id={openId} onClose={closeDrawer} />
    </AdminShell>
  );
}
