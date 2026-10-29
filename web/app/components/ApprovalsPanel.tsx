"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Loader2, Settings, X } from "lucide-react";
import { api } from "../lib/api";
import { firePulseAtElement } from "../lib/pulseBus";
import PaginationControls from "./PaginationControls";
import { PAGE_SIZE_OPTIONS, pageForSizeChange } from "../lib/usePagination";
import { listApprovalsPage } from "./approvalsServerPaging";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Alert, AlertDescription } from "./ui/alert";
import { Card, CardContent, CardHeader } from "./ui/card";
import { Checkbox } from "./ui/checkbox";
import { Label } from "./ui/label";
import Select from "./Select";
import { useTranslations } from "../lib/i18n";
import { errorMessage } from "../lib/errors";
import BrandText from "./BrandText";
import ApprovalsTable from "./approvals/ApprovalsTable";
import ApprovalDetail from "./approvals/ApprovalDetail";
import ApprovalFilters from "./approvals/ApprovalFilters";
import ApprovalContinuationPanel from "./approvals/ApprovalContinuationPanel";
import AutonomyTierExplainer from "./approvals/AutonomyTierExplainer";
import BulkDecisionBar, { type BulkMode, type BulkResult } from "./approvals/BulkDecisionBar";
import ToolApprovalRules from "./approvals/ToolApprovalRules";
import { ApprovalNotifyOptIn, useNewApprovalWatcher, useNotifyPreference } from "./approvals/ApprovalNotifications";
import { useApprovalExpiryTtl } from "./approvals/approvalExpiry";
import EmptyState from "./EmptyState";
import { MousePointerClick } from "lucide-react";
import { CHANNEL_ACTION_TYPES, STATUS_FILTERS, type ApprovalRequestItem } from "./approvals/approvalsModel";
import {
  NO_FILTERS,
  filterOptions,
  hasClientFilters,
  isToolCallRequest,
  matchesFilters,
  requestToolName,
  serverStatusFor,
  type QueueFilters,
} from "./approvals/approvalsQueueModel";

// Re-exported so existing importers (and tests) keep their import path.
export { paymentContext, proposalContext, actorLabel } from "./approvals/approvalsModel";

// Autonomy/Guardrails human-in-the-loop inbox: a filterable request table drives a review panel
// (context, proposed action, decision controls, audit trail). List/detail pieces live in ./approvals.
//
// The server filters only by status. Agent / tool / age / "expired" are applied here, so while one of them is
// active the panel reads one larger window of the newest requests (FILTER_WINDOW) instead of a server page and
// pages it locally; the table footer says when that window may not hold everything.
export const FILTER_WINDOW = 200;

export interface ApprovalsPanelProps {
  isAdmin: boolean;
  /** Deep link: select this request (read directly if it is not in the loaded list). */
  initialRequestId?: string | null;
  /** Deep link: start with these filters / this status ("" = all). */
  initialFilters?: Partial<QueueFilters>;
  initialStatus?: string | null;
  /** Deep link: open the "policy override" area (tool rules). */
  initialShowRules?: boolean;
  /** Tests shorten the new-request poll. */
  notifyPollMs?: number;
}

export default function ApprovalsPanel({
  isAdmin,
  initialRequestId = null,
  initialFilters,
  initialStatus = null,
  initialShowRules = false,
  notifyPollMs,
}: ApprovalsPanelProps) {
  const t = useTranslations("approvalsPanel");
  const q = useTranslations("approvalsQueue");
  const [statusFilter, setStatusFilter] = useState(
    initialStatus != null && STATUS_FILTERS.includes(initialStatus) ? initialStatus : "PENDING"
  );
  const [filters, setFilters] = useState<QueueFilters>({ ...NO_FILTERS, ...initialFilters });
  const [loaded, setLoaded] = useState<ApprovalRequestItem[]>([]);
  const [windowMayBeTruncated, setWindowMayBeTruncated] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(initialRequestId);
  const [pinned, setPinned] = useState<ApprovalRequestItem | null>(null);
  const [checked, setChecked] = useState<ReadonlySet<string>>(() => new Set());
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [liveNote, setLiveNote] = useState<string | null>(null);
  const [lastDecided, setLastDecided] = useState<ApprovalRequestItem | null>(null);
  const [note, setNote] = useState("");
  // Answers to the fields a workflow approval node asked the approver for.
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [showPolicyForm, setShowPolicyForm] = useState(initialShowRules);

  const [actionType, setActionType] = useState("");
  const [requiresApproval, setRequiresApproval] = useState(true);

  // Server paging: limit/offset on the list, X-Total-Count for the pager.
  const [page, setPageState] = useState(1);
  const [pageSize, setPageSizeState] = useState(PAGE_SIZE_OPTIONS[0]);
  const [serverTotal, setServerTotal] = useState(0);
  const [pendingTotal, setPendingTotal] = useState(0);
  const pageRef = useRef({ page: 1, pageSize: PAGE_SIZE_OPTIONS[0] });
  pageRef.current = { page, pageSize };
  const filtersRef = useRef(filters);
  filtersRef.current = filters;
  const detailRef = useRef<HTMLDivElement | null>(null);

  const ttlSeconds = useApprovalExpiryTtl();
  const windowed = hasClientFilters(filters, statusFilter);

  const refresh = useCallback(
    async (status: string, p?: { page: number; pageSize: number }, f?: QueueFilters, quiet = false) => {
      setError(null);
      if (!quiet) setLoading(true);
      const cur = p ?? pageRef.current;
      const flt = f ?? filtersRef.current;
      const useWindow = hasClientFilters(flt, status);
      try {
        const res = useWindow
          ? await listApprovalsPage(serverStatusFor(status), 1, FILTER_WINDOW)
          : await listApprovalsPage(status, cur.page, cur.pageSize);
        const data: ApprovalRequestItem[] = res.rows as ApprovalRequestItem[];
        if (!useWindow && data.length === 0 && cur.page > 1) {
          // The last row of this page was just decided: step back instead of showing an empty page.
          setPageState(cur.page - 1);
          return;
        }
        setServerTotal(res.total ?? (cur.page - 1) * cur.pageSize + data.length + (res.hasMore ? 1 : 0));
        setWindowMayBeTruncated(useWindow && (res.hasMore || (res.total ?? 0) > data.length));
        void api
          .countApprovals("PENDING")
          .then(setPendingTotal)
          .catch((): void => undefined);
        setLoaded(data);
        setLoading(false);
      } catch (err: any) {
        setError(err.message || t("loadRequestsFailed"));
        setLoading(false);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []
  );

  const filtersKey = `${filters.agent}|${filters.tool}|${filters.age}`;
  useEffect(() => {
    void refresh(statusFilter, { page, pageSize }, filters);
    // Windowed reads do not depend on the page: paging there is local.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, filtersKey, windowed ? 0 : page, windowed ? 0 : pageSize]);

  // Rows after the local filters. The age rule needs "now"; recomputed whenever the rows or filters change.
  const visible = useMemo(
    () => (windowed ? loaded.filter((r) => matchesFilters(r, filters, statusFilter, Date.now())) : loaded),
    [windowed, loaded, filters, statusFilter]
  );
  const totalItems = windowed ? visible.length : serverTotal;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  const pageItems = useMemo(
    () =>
      windowed
        ? visible.slice((Math.min(page, totalPages) - 1) * pageSize, Math.min(page, totalPages) * pageSize)
        : visible,
    [windowed, visible, page, pageSize, totalPages]
  );
  const options = useMemo(() => filterOptions(loaded), [loaded]);

  // The selection: the one asked for (clicked, deep link, pinned) while it is still on screen, else the first row.
  // Derived in render so the first paint of a loaded list already shows its detail.
  const activeId =
    selectedId && (pageItems.some((r) => r.id === selectedId) || selectedId === pinned?.id)
      ? selectedId
      : (pageItems[0]?.id ?? null);

  const selected = useMemo(
    () => pageItems.find((r) => r.id === activeId) || (pinned && pinned.id === activeId ? pinned : null),
    [pageItems, activeId, pinned]
  );

  // A reason or form answers typed for one request must never ride along to another.
  useEffect(() => {
    setNote("");
    setFormValues({});
  }, [activeId]);

  // Ticks only survive for rows that are still pending on screen.
  useEffect(() => {
    setChecked((prev) => {
      if (prev.size === 0) return prev;
      const live = new Set(pageItems.filter((r) => r.status === "PENDING").map((r) => r.id));
      const next = new Set([...prev].filter((id) => live.has(id)));
      return next.size === prev.size ? prev : next;
    });
  }, [pageItems]);

  // Deep link to one request (a notification, an agent page): read it directly so it opens even when it is not
  // on the first page, and show the status list it belongs to.
  useEffect(() => {
    if (!initialRequestId) return undefined;
    let live = true;
    Promise.resolve()
      .then(() => api.getApproval(initialRequestId, [403, 404], true))
      .then((item: ApprovalRequestItem | null) => {
        if (!live || !item) return;
        setPinned(item);
        setSelectedId(item.id);
        if (initialStatus == null && item.status !== "PENDING") setStatusFilter("");
      })
      .catch(() => {
        if (live) setError(q("deepLinkNotFound"));
      });
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialRequestId]);

  const pendingCount = pendingTotal;
  const setPage = (p: number) => setPageState(Math.min(Math.max(1, p), totalPages));
  const setPageSize = (size: number) => {
    const next = Number(size);
    if (!Number.isFinite(next) || next < 1) return;
    setPageState(pageForSizeChange(page, pageSize, next));
    setPageSizeState(next);
  };

  // New pending requests: refresh a pending list in place, announce politely, and (opted in, tab in the
  // background) raise a system notification whose click opens the request.
  const notifyPref = useNotifyPreference();
  const openRequest = useCallback(async (id: string) => {
    try {
      const item = (await api.getApproval(id, [403, 404], true)) as ApprovalRequestItem | null;
      if (item) {
        setPinned(item);
        setSelectedId(item.id);
        if (item.status === "PENDING") setStatusFilter("PENDING");
      }
    } catch {
      /* the list still shows it if it exists */
    }
  }, []);
  useNewApprovalWatcher({
    poll: async () => (await listApprovalsPage("PENDING", 1, 20)).rows as ApprovalRequestItem[],
    notify: notifyPref.on,
    pollMs: notifyPollMs,
    onOpen: (id) => void openRequest(id),
    onNew: (fresh) => {
      setLiveNote(q("newArrived", { count: fresh.length }));
      if (statusFilter === "PENDING") void refresh(statusFilter, undefined, undefined, true);
    },
  });

  const afterDecision = useCallback(
    async (decided: ApprovalRequestItem | null, outcomeStatus: string) => {
      if (decided && isToolCallRequest(decided)) {
        setLastDecided({
          ...decided,
          status: outcomeStatus,
          decidedAt: decided.decidedAt ?? new Date().toISOString(),
        });
      }
      setPinned((prev) => (prev && decided && prev.id === decided.id ? null : prev));
      await refresh(statusFilter);
      detailRef.current?.focus();
    },
    [refresh, statusFilter]
  );

  async function handleApprove(e: React.MouseEvent<HTMLButtonElement>) {
    if (!selected) return;
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      const resp = (await api.approveRequest(selected.id, null, formValues)) as Partial<ApprovalRequestItem> | null;
      setFormValues({});
      setSuccess(t("approvedSuccess"));
      setNote("");
      // Reactive background (GlobalBackgroundFX.js) - a visible green
      // confirm pulse right where the click happened, direct response to
      // "background... should animate once user performs some action,
      // according to action performed."
      if (e?.currentTarget) firePulseAtElement(e.currentTarget, "success");
      await afterDecision(
        { ...selected, ...resp, status: "APPROVED" } as ApprovalRequestItem,
        resp?.status && resp.status !== "PENDING" ? resp.status : "APPROVED"
      );
    } catch (err) {
      setError(errorMessage(err, t("approveFailed")));
    } finally {
      setBusy(false);
    }
  }

  async function handleReject(e: React.MouseEvent<HTMLButtonElement>) {
    if (!selected) return;
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      const resp = (await api.rejectRequest(
        selected.id,
        null,
        note.trim() || null
      )) as Partial<ApprovalRequestItem> | null;
      setSuccess(t("rejectedSuccess"));
      setNote("");
      if (e?.currentTarget) firePulseAtElement(e.currentTarget, "danger");
      await afterDecision({ ...selected, ...resp, status: "REJECTED" } as ApprovalRequestItem, "REJECTED");
    } catch (err) {
      setError(errorMessage(err, t("rejectFailed")));
    } finally {
      setBusy(false);
    }
  }

  // Bulk: one request after another (each approved call runs inside its own approve request). A failure on one
  // never stops the rest; every failure is reported by id.
  const runBulk = useCallback(
    async (mode: BulkMode, reason: string | null, onProgress: (done: number) => void): Promise<BulkResult> => {
      const targets = pageItems.filter((r) => checked.has(r.id));
      const failed: BulkResult["failed"] = [];
      let done = 0;
      for (const target of targets) {
        try {
          if (mode === "approve") await api.approveRequest(target.id, null, undefined);
          else await api.rejectRequest(target.id, null, reason);
          done += 1;
        } catch (err) {
          failed.push({
            id: target.id,
            message: errorMessage(err, t(mode === "approve" ? "approveFailed" : "rejectFailed")),
          });
        }
        onProgress(done + failed.length);
      }
      setChecked(new Set());
      setSuccess(null);
      await refresh(statusFilter);
      return { done, failed };
    },
    [pageItems, checked, refresh, statusFilter, t]
  );

  async function handleSetPolicy(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!actionType.trim()) {
      setError(t("actionTypeRequired"));
      return;
    }
    setBusy(true);
    try {
      await api.setApprovalPolicy(actionType.trim(), requiresApproval);
      setSuccess(
        t("policySetSuccess", { actionType, mode: requiresApproval ? t("requiresApprovalMode") : t("autoApproveMode") })
      );
      setActionType("");
    } catch (err: any) {
      setError(err.message || t("policyUpdateFailed"));
    } finally {
      setBusy(false);
    }
  }

  const channelActionTypeOptions = CHANNEL_ACTION_TYPES.map((c) => ({ value: c.value, label: t(c.labelKey) }));
  const checkedItems = pageItems.filter((r) => checked.has(r.id));
  const toggleChecked = (id: string) =>
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const toggleAll = (ids: string[], on: boolean) => setChecked(on ? new Set(ids) : new Set());
  const changeFilters = (next: QueueFilters) => {
    setFilters(next);
    setPageState(1);
    setChecked(new Set());
  };

  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="grid gap-1">
          <h2 className="text-lg font-semibold tracking-tight">
            <BrandText text={t("title")} />
          </h2>
          <p className="text-sm text-muted-foreground">
            <BrandText text={t("subtitle", { count: pendingCount })} />
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <ApprovalNotifyOptIn
            permission={notifyPref.permission}
            on={notifyPref.on}
            onEnable={() => void notifyPref.enable()}
            onDisable={notifyPref.disable}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            title={t("policyOverrideTooltip")}
            aria-expanded={showPolicyForm}
            onClick={() => setShowPolicyForm((v) => !v)}
          >
            <Settings aria-hidden="true" size={14} />
            {t("policyOverrideButton")}
          </Button>
        </div>
      </div>

      <div aria-live="polite" className="sr-only" data-testid="approvals-live">
        {liveNote}
      </div>

      {error && (
        <Alert variant="error">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {success && (
        <Alert variant="success" role="status">
          <AlertDescription>{success}</AlertDescription>
        </Alert>
      )}

      {lastDecided && (
        <Card className="gap-2 py-3" data-testid="last-decision">
          <CardContent className="grid gap-1">
            <div className="flex items-center justify-between gap-2">
              <span className="min-w-0 break-words text-sm font-medium">
                {q("lastDecisionTitle", { tool: requestToolName(lastDecided) })}
              </span>
              <button
                type="button"
                aria-label={q("lastDecisionClose")}
                title={q("lastDecisionClose")}
                onClick={() => setLastDecided(null)}
                className="-me-1 shrink-0 rounded-md p-1 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground"
              >
                <X size={14} className="size-3.5" aria-hidden="true" />
              </button>
            </div>
            <ApprovalContinuationPanel item={lastDecided} />
          </CardContent>
        </Card>
      )}

      {showPolicyForm && (
        <Card className="gap-4 py-5">
          <CardContent className="grid gap-6">
            <form onSubmit={handleSetPolicy} className="grid max-w-xl gap-3">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                {t("policyOverrideLabel")} {!isAdmin && <span className="normal-case">{t("tenantAdminOnly")}</span>}
              </div>
              <Select
                searchable
                allowCustom
                value={actionType}
                onChange={(e) => setActionType(e.target.value)}
                options={channelActionTypeOptions}
                placeholder={t("searchChannelsPlaceholder")}
              />
              <div className="flex items-center gap-2">
                <Checkbox
                  id="appr-requires"
                  checked={requiresApproval}
                  onCheckedChange={(v) => setRequiresApproval(v === true)}
                />
                <Label htmlFor="appr-requires">{t("requiresApprovalCheckbox")}</Label>
              </div>
              <div>
                <Button type="submit" size="sm" disabled={busy}>
                  {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
                  {t("savePolicy")}
                </Button>
              </div>
            </form>
            <ToolApprovalRules canEdit={isAdmin} />
          </CardContent>
        </Card>
      )}

      <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <Card className="gap-0 overflow-hidden py-0">
          <CardHeader className="gap-2 border-b border-border px-4 py-3">
            <div
              className="flex flex-wrap gap-1.5"
              role="group"
              aria-label={t("statusFilterLabel")}
              data-testid="approvals-status-filter"
            >
              {STATUS_FILTERS.map((s) => (
                <Button
                  key={s || "all"}
                  type="button"
                  size="sm"
                  variant={statusFilter === s ? "default" : "outline"}
                  aria-pressed={statusFilter === s}
                  onClick={() => {
                    setStatusFilter(s);
                    setPageState(1);
                    setChecked(new Set());
                  }}
                >
                  {s ? q(`status_${s}`) : t("allOption")}
                </Button>
              ))}
            </div>
            <ApprovalFilters filters={filters} onChange={changeFilters} agents={options.agents} tools={options.tools} />
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Badge variant="secondary">{totalItems}</Badge>
              <span>{t("oldestFirstSuffix")}</span>
            </div>
            {windowed && windowMayBeTruncated && (
              <p className="text-xs text-muted-foreground" data-testid="approvals-window-note">
                {q("filterWindowNote", { count: FILTER_WINDOW })}
              </p>
            )}
          </CardHeader>
          <BulkDecisionBar selected={checkedItems} onRun={runBulk} onClear={() => setChecked(new Set())} />
          <ApprovalsTable
            rows={pageItems}
            loading={loading}
            selectedId={activeId}
            onSelect={setSelectedId}
            t={t}
            checkedIds={checked}
            onToggleChecked={toggleChecked}
            onToggleAll={toggleAll}
            ttlSeconds={ttlSeconds}
          />
          <div className="border-t border-border px-4 py-3">
            <PaginationControls
              page={page}
              totalPages={totalPages}
              totalItems={totalItems}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              itemLabel={t("itemLabelRequests")}
            />
          </div>
        </Card>

        <div
          className="outline-none lg:sticky lg:top-4"
          ref={detailRef}
          tabIndex={-1}
          role="region"
          aria-label={q("detailRegionLabel")}
        >
          {selected ? (
            <ApprovalDetail
              item={selected}
              note={note}
              onNoteChange={setNote}
              formValues={formValues}
              onFormValueChange={(field, v) => setFormValues((prev) => ({ ...prev, [field]: v }))}
              busy={busy}
              ttlSeconds={ttlSeconds}
              onApprove={handleApprove}
              onReject={handleReject}
              t={t}
            />
          ) : (
            <Card className="py-6">
              <EmptyState icon={MousePointerClick} title={t("selectRequestHint")} />
            </Card>
          )}
        </div>
      </div>

      <AutonomyTierExplainer />
    </div>
  );
}
