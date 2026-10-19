"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Loader2, Settings } from "lucide-react";
import { api } from "../lib/api";
import { firePulseAtElement } from "../lib/pulseBus";
import Select from "./Select";
import PaginationControls from "./PaginationControls";
import { PAGE_SIZE_OPTIONS, pageForSizeChange } from "../lib/usePagination";
import { listApprovalsPage } from "./approvalsServerPaging";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Alert, AlertDescription } from "./ui/alert";
import { Card, CardContent, CardHeader } from "./ui/card";
import { Checkbox } from "./ui/checkbox";
import { Label } from "./ui/label";
import { useTranslations } from "../lib/i18n";
import { errorMessage } from "../lib/errors";
import BrandText from "./BrandText";
import ApprovalsTable from "./approvals/ApprovalsTable";
import ApprovalDetail from "./approvals/ApprovalDetail";
import EmptyState from "./EmptyState";
import { MousePointerClick } from "lucide-react";
import { CHANNEL_ACTION_TYPES, STATUS_FILTERS, type ApprovalRequestItem } from "./approvals/approvalsModel";

// Re-exported so existing importers (and tests) keep their import path.
export { paymentContext, proposalContext, actorLabel } from "./approvals/approvalsModel";

// Autonomy/Guardrails human-in-the-loop inbox: a filterable request table drives a review panel
// (context, proposed action, decision controls, audit trail). List/detail pieces live in ./approvals.

export default function ApprovalsPanel({ isAdmin }: { isAdmin: boolean }) {
  const t = useTranslations("approvalsPanel");
  const [statusFilter, setStatusFilter] = useState("PENDING");
  const [requests, setRequests] = useState<ApprovalRequestItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [note, setNote] = useState("");
  // Answers to the fields a workflow approval node asked the approver for.
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [showPolicyForm, setShowPolicyForm] = useState(false);

  const [actionType, setActionType] = useState("");
  const [requiresApproval, setRequiresApproval] = useState(true);

  // Server paging: limit/offset on the list, X-Total-Count for the pager.
  const [page, setPageState] = useState(1);
  const [pageSize, setPageSizeState] = useState(PAGE_SIZE_OPTIONS[0]);
  const [totalItems, setTotalItems] = useState(0);
  const [pendingTotal, setPendingTotal] = useState(0);
  const pageRef = useRef({ page: 1, pageSize: PAGE_SIZE_OPTIONS[0] });
  pageRef.current = { page, pageSize };

  const refresh = useCallback(async (status: string, p?: { page: number; pageSize: number }) => {
    setError(null);
    setLoading(true);
    const cur = p ?? pageRef.current;
    try {
      const res = await listApprovalsPage(status, cur.page, cur.pageSize);
      const data: ApprovalRequestItem[] = res.rows as ApprovalRequestItem[];
      if (data.length === 0 && cur.page > 1) {
        // The last row of this page was just decided: step back instead of showing an empty page.
        setPageState(cur.page - 1);
        return;
      }
      setTotalItems(res.total ?? (cur.page - 1) * cur.pageSize + data.length + (res.hasMore ? 1 : 0));
      void api.countApprovals("PENDING").then(setPendingTotal).catch((): void => undefined);
      setRequests(data);
      setLoading(false);
      setSelectedId((prev) => (prev && data.some((r) => r.id === prev) ? prev : (data[0]?.id ?? null)));
    } catch (err: any) {
      setError(err.message || t("loadRequestsFailed"));
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    void refresh(statusFilter, { page, pageSize });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, page, pageSize]);

  const selected = useMemo(() => requests.find((r) => r.id === selectedId) || null, [requests, selectedId]);
  const pendingCount = pendingTotal;
  const pageItems = requests;
  const totalPages = Math.max(1, Math.ceil(totalItems / pageSize));
  const setPage = (p: number) => setPageState(Math.min(Math.max(1, p), totalPages));
  const setPageSize = (size: number) => {
    const next = Number(size);
    if (!Number.isFinite(next) || next < 1) return;
    setPageState(pageForSizeChange(page, pageSize, next));
    setPageSizeState(next);
  };

  async function handleApprove(e: React.MouseEvent<HTMLButtonElement>) {
    if (!selected) return;
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.approveRequest(selected.id, null, formValues);
      setFormValues({});
      setSuccess(t("approvedSuccess"));
      setNote("");
      // Reactive background (GlobalBackgroundFX.js) - a visible green
      // confirm pulse right where the click happened, direct response to
      // "background... should animate once user performs some action,
      // according to action performed."
      if (e?.currentTarget) firePulseAtElement(e.currentTarget, "success");
      await refresh(statusFilter);
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
      await api.rejectRequest(selected.id, null, note.trim() || null);
      setSuccess(t("rejectedSuccess"));
      setNote("");
      if (e?.currentTarget) firePulseAtElement(e.currentTarget, "danger");
      await refresh(statusFilter);
    } catch (err) {
      setError(errorMessage(err, t("rejectFailed")));
    } finally {
      setBusy(false);
    }
  }

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

      {showPolicyForm && (
        <Card className="gap-4 py-5">
          <CardContent>
            <form onSubmit={handleSetPolicy} className="grid max-w-xl gap-3">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                {t("policyOverrideLabel")}{" "}
                {!isAdmin && <span className="normal-case">{t("tenantAdminOnly")}</span>}
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
                  }}
                >
                  {s ? s.replace(/_/g, " ") : t("allOption")}
                </Button>
              ))}
            </div>
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Badge variant="secondary">{totalItems}</Badge>
              <span>{t("oldestFirstSuffix")}</span>
            </div>
          </CardHeader>
          <ApprovalsTable
            rows={pageItems}
            loading={loading}
            selectedId={selectedId}
            onSelect={setSelectedId}
            t={t}
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

        <div className="lg:sticky lg:top-4">
          {selected ? (
            <ApprovalDetail
              item={selected}
              note={note}
              onNoteChange={setNote}
              formValues={formValues}
              onFormValueChange={(field, v) => setFormValues((prev) => ({ ...prev, [field]: v }))}
              busy={busy}
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
    </div>
  );
}
