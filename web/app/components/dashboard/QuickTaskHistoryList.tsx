"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { CheckCircle2, XCircle, X, Cpu } from "lucide-react";
import { api } from "../../lib/api";
import { useAuth } from "../AuthProvider";
import PaginationControls from "../PaginationControls";
import { pageForSizeChange } from "../../lib/usePagination";
import { fetchQuickTaskPage } from "../../lib/api/quickTaskHistory";
import { ListError, ListSkeleton } from "../tenantLists/ListStates";
import { useTranslations } from "../../lib/i18n";
import { isBillingEnabled } from "../../lib/billing-flag";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import BrandText from "../BrandText";
import PoweredByChip, { inferSource } from "../PoweredByChip";
import { cn } from "../../lib/utils";
import { errorMessage } from "../../lib/errors";

// Direct feedback: "task history should be in another page" - previously
// this list + its detail modal lived inline on the Quick Task screen itself
// (SimpleTaskRunner.js), toggled open/closed in place. Moved here so
// /quick-task/history (see that route's page.js) can give it the whole
// screen, following the same "same component/data source, just given the
// whole page" precedent /admin/audit-tail already established for
// AdminEventTail. Data source is unchanged: GET /api/quick-task/recent for
// the list, GET /api/quick-task/{taskId} for the full untruncated log
// (QuickTaskRun, persisted since #608) when a row is opened.

// Wire shapes for the three endpoints this screen reads, mirrored from the
// backend records that serialize them (Java Instant -> ISO string, BigDecimal
// -> JSON number over Jackson): QuickTaskRunSummaryResponse.java,
// QuickTaskRunDetailResponse.java (+ its nested ToolCallEntry), and
// LlmCallLogController.LlmCallLogEntry. Declared here at the consumer because
// api.ts's quick-task/llm-usage fetchers are still untyped (toJson returns
// Promise<any>) - once that file's own typing pass gives them real return
// types, these should move there and be imported instead.
type QuickTaskRunStatus = "RUNNING" | "COMPLETED" | "FAILED";
type QuickTaskChannel = "WHATSAPP" | "SMS" | "CALL" | "LOG";

interface QuickTaskRunSummary {
  taskId: string;
  channel: QuickTaskChannel;
  status: QuickTaskRunStatus;
  requestMessage: string;
  startedAt: string;
}

interface QuickTaskToolCallEntry {
  toolName: string;
  toolInput: string | null;
  toolOutput: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  completedAt: string | null;
}

interface QuickTaskRunDetail {
  taskId: string;
  agentId: string | null;
  channel: QuickTaskChannel;
  status: QuickTaskRunStatus;
  requestMessage: string;
  resultText: string | null;
  deliveryNote: string | null;
  errorMessage: string | null;
  startedAt: string;
  completedAt: string | null;
  toolCalls: QuickTaskToolCallEntry[] | null;
  // Engine disclosure added to QuickTaskRunDetailResponse - which agent
  // powered the run and on what provider/model. Optional: null on older
  // core builds or when the agent record can't be resolved anymore.
  agentName?: string | null;
  providerName?: string | null;
  model?: string | null;
}

// One LLM call attributed to this task (sessionId === taskId linkage, see
// api.getLlmUsageForTask's comment) - LlmCallLogController.LlmCallLogEntry.
interface TaskLlmCallEntry {
  id: string;
  agentId: string | null;
  sessionId: string;
  providerName: string;
  // String in the Java record, i.e. a nullable reference - a call that failed
  // before model resolution serializes model: null, so typing it string here
  // would just move the NPE into the template literal below.
  model: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  estimatedCostUsd: number | null;
  success: boolean;
  errorMessage: string | null;
  latencyMs: number;
  startedAt: string;
}

// Scoped translator returned by useTranslations - same call shape the other
// migrated components spell out (e.g. TrySandbox.tsx), until i18n.tsx itself
// gets typed.
type TranslateFn = (key: string, values?: Record<string, unknown>) => string;

// Real `outcome`/status -> a token-driven Badge tone, off the design-system
// contract's execution-state hues (docs/DESIGN-SYSTEM-CONTRACT-2026-07-29.md
// §3) rather than raw Tailwind palette utilities - COMPLETED reads as
// --state-success, FAILED as --state-error, anything still in flight stays
// neutral. Same 12%/32% wash/border recipe .state-pill itself uses, applied
// via Tailwind arbitrary values since this call site keeps the shared
// shadcn <Badge/> rather than swapping in the bespoke .state-pill markup.
function statusTone(status: QuickTaskRunStatus) {
  if (status === "COMPLETED") {
    return "text-[var(--state-success)] border-[color-mix(in_srgb,var(--state-success)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-success)_12%,transparent)]";
  }
  if (status === "FAILED") {
    return "text-[var(--state-error)] border-[color-mix(in_srgb,var(--state-error)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-error)_12%,transparent)]";
  }
  return "border-border bg-secondary/40 text-muted-foreground";
}

// Direct feedback ("PLATFORM_HOSTED details should not be visible in
// tenant maintanance page") - same leak as billing/LlmSpendSection's identical
// helper: ProviderName.PLATFORM_HOSTED is an internal accounting label a
// FREE-plan tenant's calls get attributed to (see that enum's javadoc), not
// something meant to reach a tenant's own screen - this "LLM USED" section
// (isAdmin-gated, but still a tenant admin, not a platform admin) was
// printing it raw.
function displayProviderName(name: string, t: TranslateFn) {
  return name === "PLATFORM_HOSTED" ? <BrandText text={t("platformHostedDisplay")} /> : name;
}

// A single labeled block inside the detail modal.
// `label` is ReactNode, not string - the LLM USED section passes a fragment
// with an inline <Cpu/> icon.
function DetailField({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <div className="space-y-1.5">
      <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{label}</div>
      {children}
    </div>
  );
}

// One row in a trace / LLM-usage list — shared shape across both sections.
function TraceRow({ ok, title, children }: { ok: boolean; title: ReactNode; children?: ReactNode }) {
  return (
    <div className="flex items-start gap-2.5 rounded-lg border border-[var(--surface-border)] bg-[var(--surface-card-alt)] px-3 py-2">
      <span className="mt-0.5 shrink-0">
        {ok ? (
          <CheckCircle2 size={14} className="text-[var(--state-success)]" aria-hidden="true" />
        ) : (
          <XCircle size={14} className="text-destructive" aria-hidden="true" />
        )}
      </span>
      <div className="min-w-0 flex-1 space-y-0.5">
        <div className="break-words text-sm font-medium text-foreground">{title}</div>
        {children}
      </div>
    </div>
  );
}

export default function QuickTaskHistoryList() {
  const t = useTranslations("quickTaskHistoryList");
  const { hasPermission } = useAuth();
  const [history, setHistory] = useState<QuickTaskRunSummary[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Server paging: GET /api/quick-task/recent takes limit/offset and answers the real run count.
  const [page, setPage] = useState(1);
  const [pageSize, setPageSizeState] = useState(25);
  const [total, setTotal] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);

  const [detailTaskId, setDetailTaskId] = useState<string | null>(null);
  const [detail, setDetail] = useState<QuickTaskRunDetail | null>(null);
  const [detailBusy, setDetailBusy] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  // "tenant admin should be able to see the llm used for ask me" - fetched
  // alongside the task detail, admin-only (GET /api/admin/llm-usage/tasks/{id}
  // is ROLE_tenant-admin gated server-side too - this client check just
  // avoids a guaranteed 403 for non-admin consumers).
  const [llmUsage, setLlmUsage] = useState<TaskLlmCallEntry[] | null>(null);

  const detailModalRef = useRef<HTMLDivElement | null>(null);
  const previouslyFocusedElementRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    let cancelled = false;
    setBusy(true);
    setError(null);
    fetchQuickTaskPage<QuickTaskRunSummary>((page - 1) * pageSize, pageSize)
      .then((res) => {
        if (cancelled) return;
        setHistory(res.rows);
        setTotal(res.total);
        setHasMore(res.hasMore);
      })
      .catch((err) => {
        if (!cancelled) setError(err.message || t("couldNotLoadHistory"));
      })
      .finally(() => {
        if (!cancelled) setBusy(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, pageSize, reloadKey]);

  async function openTaskDetail(id: string) {
    setDetailTaskId(id);
    setDetail(null);
    setDetailError(null);
    setDetailBusy(true);
    setLlmUsage(null);
    try {
      setDetail(await api.getQuickTask(id));
    } catch (err) {
      setDetailError(errorMessage(err, t("couldNotLoadTask")));
    } finally {
      setDetailBusy(false);
    }
    if (hasPermission("VIEW_USAGE")) {
      // Best-effort, separate from the main detail load - a failure here
      // (e.g. no LLM calls logged for a still-running task) shouldn't block
      // the rest of the detail view.
      api
        .getLlmUsageForTask(id)
        .then(setLlmUsage)
        .catch(() => setLlmUsage([]));
    }
  }

  function closeTaskDetail() {
    setDetailTaskId(null);
    setDetail(null);
    setDetailError(null);
    setLlmUsage(null);
  }

  // Pragmatic focus trap for the detail modal: focus the first focusable
  // element on open, cycle Tab within the modal while it's open, close on
  // Escape, and restore focus to whatever triggered the modal on close.
  useEffect(() => {
    if (!detailTaskId) return undefined;

    previouslyFocusedElementRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const getFocusableElements = (): HTMLElement[] => {
      const modalEl = detailModalRef.current;
      if (!modalEl) return [];
      return Array.from(
        modalEl.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
        )
      ).filter((el) => !el.hasAttribute("disabled"));
    };

    const focusables = getFocusableElements();
    (focusables[0] || detailModalRef.current)?.focus();

    // DOM KeyboardEvent (not React's synthetic one) - this listener is
    // attached straight to `document`.
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.stopPropagation();
        closeTaskDetail();
        return;
      }
      if (e.key === "Tab") {
        const items = getFocusableElements();
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      previouslyFocusedElementRef.current?.focus?.();
    };
  }, [detailTaskId]);

  const pageItems = history;
  const setPageSize = (size: number) => {
    setPage(pageForSizeChange(page, pageSize, size));
    setPageSizeState(size);
  };

  return (
    <>
      {error && (
        <div className="mb-4">
          <ListError message={error} onRetry={() => setReloadKey((k) => k + 1)} />
        </div>
      )}

      <Card className="gap-0 overflow-hidden p-0">
        <Table>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="w-[220px]">{t("colStarted")}</TableHead>
              <TableHead className="w-[130px]">{t("colStatus")}</TableHead>
              <TableHead>{t("colRequest")}</TableHead>
              <TableHead className="w-[120px] text-end">{t("colTaskId")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {/* Annotated inline because usePagination is itself still untyped
                (items: any), so pageItems carries no element type yet. */}
            {pageItems.map((h: QuickTaskRunSummary) => (
              <TableRow key={h.taskId}>
                <TableCell className="whitespace-nowrap text-sm text-muted-foreground tabular-nums">
                  {new Date(h.startedAt).toLocaleString()}
                </TableCell>
                <TableCell>
                  <Badge variant="outline" className={cn("font-medium", statusTone(h.status))}>
                    {h.status}
                  </Badge>
                </TableCell>
                <TableCell className="min-w-[16rem] max-w-[28rem] truncate text-sm text-foreground">
                  <button
                    type="button"
                    className="max-w-full truncate text-start underline-offset-2 hover:underline"
                    onClick={() => openTaskDetail(h.taskId)}
                  >
                    {h.requestMessage}
                  </button>
                </TableCell>
                <TableCell className="text-end font-mono text-xs text-muted-foreground">
                  <button type="button" className="hover:underline" onClick={() => openTaskDetail(h.taskId)}>
                    {h.taskId.slice(0, 8)}
                  </button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>

        {/* Kept the .ax-mnt-appr-empty hook the Playwright suite selects on for
            the loading / empty states (12-quick-task.spec.js). */}
        {busy && history.length === 0 && (
          <div className="ax-mnt-appr-empty px-4 py-6">
            <ListSkeleton rows={5} label={t("loadingHistory")} />
          </div>
        )}
        {!busy && history.length === 0 && !error && (
          <div className="ax-mnt-appr-empty px-4 py-14 text-center text-sm text-muted-foreground">
            {t("emptyHistory")}
          </div>
        )}
      </Card>

      <PaginationControls
        page={page}
        totalItems={total ?? undefined}
        hasMore={hasMore}
        pageItemCount={history.length}
        loading={busy}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
        itemLabel={t("itemLabelTasks")}
      />

      {detailTaskId &&
        typeof document !== "undefined" &&
        createPortal(
          <div
            className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/50 p-4 backdrop-blur-sm sm:p-8"
            role="presentation"
            onClick={(e) => {
              if (e.target === e.currentTarget) closeTaskDetail();
            }}
          >
            <div
              ref={detailModalRef}
              tabIndex={-1}
              role="dialog"
              aria-modal="true"
              aria-labelledby="quick-task-detail-title"
              className="my-auto w-full max-w-2xl rounded-xl border border-border bg-card p-6 shadow-xl outline-none"
            >
              <div className="mb-5 flex items-start justify-between gap-4">
                <div className="space-y-2">
                  <p id="quick-task-detail-title" className="text-lg font-semibold tracking-tight text-foreground">
                    {t("taskDetailTitle", { id: detailTaskId.slice(0, 8) })}
                  </p>
                  {detail && (
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge variant="outline" className={cn("font-medium", statusTone(detail.status))}>
                        {detail.status}
                      </Badge>
                      {/* "customers should know what powered" a past run -
                       * shown to every user (unlike the admin-gated LLM
                       * USED billing rows below): this is the agent's
                       * configured engine, not cost data. */}
                      {(detail.providerName || detail.model) && (
                        <PoweredByChip
                          source={inferSource(detail.providerName)}
                          provider={detail.providerName}
                          model={detail.model}
                          title={detail.agentName ? `Run by the "${detail.agentName}" agent` : undefined}
                        />
                      )}
                    </div>
                  )}
                </div>
                <button
                  type="button"
                  className="flex size-8 shrink-0 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                  onClick={closeTaskDetail}
                  aria-label={t("close")}
                >
                  <X size={16} aria-hidden="true" />
                </button>
              </div>

              {detailBusy && <p className="text-sm text-muted-foreground">{t("loadingDetail")}</p>}
              {detailError && <p className="text-sm text-destructive">{detailError}</p>}

              {detail && (
                <div className="space-y-5">
                  <DetailField label={t("requestSentLabel")}>
                    <div className="whitespace-pre-wrap break-words rounded-lg border border-[var(--surface-border)] bg-[var(--surface-card-alt)] px-3 py-2 text-sm text-foreground">
                      {detail.requestMessage}
                    </div>
                  </DetailField>

                  {hasPermission("VIEW_USAGE") && llmUsage && llmUsage.length > 0 && (
                    <DetailField
                      label={
                        <>
                          <Cpu size={11} style={{ verticalAlign: "-1px", marginRight: "4px" }} aria-hidden="true" />
                          {t("llmUsedLabel")}
                        </>
                      }
                    >
                      <div className="space-y-2">
                        {llmUsage.map((c) => (
                          <TraceRow
                            key={c.id}
                            ok={c.success}
                            title={
                              c.model
                                ? `${displayProviderName(c.providerName, t)} · ${c.model}`
                                : displayProviderName(c.providerName, t)
                            }
                          >
                            <div className="text-xs text-muted-foreground">
                              {c.totalTokens != null ? t("tokensLabel", { count: c.totalTokens }) : ""}
                              {isBillingEnabled() && c.estimatedCostUsd != null
                                ? ` · ~$${Number(c.estimatedCostUsd).toFixed(4)}`
                                : ""}
                              {c.latencyMs != null ? ` · ${c.latencyMs}ms` : ""}
                            </div>
                            {!c.success && c.errorMessage && (
                              <div className="text-xs text-destructive">
                                {t("traceError", { message: c.errorMessage })}
                              </div>
                            )}
                          </TraceRow>
                        ))}
                      </div>
                    </DetailField>
                  )}

                  {detail.toolCalls?.length > 0 && (
                    <DetailField label={t("toolCallsLabel", { count: detail.toolCalls.length })}>
                      <div className="space-y-2">
                        {detail.toolCalls.map((entry, i) => (
                          <TraceRow key={i} ok={!entry.errorMessage} title={entry.toolName}>
                            {entry.toolInput && (
                              <div className="break-words text-xs text-muted-foreground">
                                {t("traceIn", { input: entry.toolInput })}
                              </div>
                            )}
                            {entry.errorMessage ? (
                              <div className="break-words text-xs text-destructive">
                                {t("traceError", { message: entry.errorMessage })}
                              </div>
                            ) : (
                              entry.toolOutput && (
                                <div className="break-words text-xs text-muted-foreground">
                                  {t("traceOut", { output: entry.toolOutput })}
                                </div>
                              )
                            )}
                          </TraceRow>
                        ))}
                      </div>
                    </DetailField>
                  )}

                  {detail.resultText && (
                    <DetailField label={t("responseBackLabel")}>
                      <div className="whitespace-pre-wrap break-words rounded-lg border border-[var(--surface-border)] bg-[var(--surface-card-alt)] px-3 py-2 text-sm text-foreground">
                        {detail.resultText}
                      </div>
                    </DetailField>
                  )}

                  {detail.deliveryNote && (
                    <DetailField label={t("deliveryLabel")}>
                      <div className="text-sm text-foreground">{detail.deliveryNote}</div>
                    </DetailField>
                  )}

                  {detail.errorMessage && (
                    <DetailField label={t("errorLabel")}>
                      <div className="text-sm text-destructive">{detail.errorMessage}</div>
                    </DetailField>
                  )}
                </div>
              )}
            </div>
          </div>,
          document.body
        )}
    </>
  );
}
