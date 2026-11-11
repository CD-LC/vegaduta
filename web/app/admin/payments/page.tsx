"use client";

import { Select } from "../../components/ui/select";
import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import type {
  MandateApprovalPolicy,
  MandatePreview,
  MandateScope,
  MandateSpend,
  MandateStatus,
  PaymentMandate,
  PaymentTransaction,
} from "../../lib/api/payments";
import { fmtPaise, rupeesToPaise, spendTone } from "../../lib/api/payments";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { EmptyState } from "../../components/ax/page-kit";
import TransactionsTable, { TXN_FETCH_LIMIT } from "./TransactionsTable";
import { loadMandateSpends } from "./paymentsData";
import { usePagedList } from "../issues/pagedLoad";
import { ErrorRetry, FilterPills, ListSkeleton, PagedList } from "../commerce/listKit";
import { usePageReady } from "../../components/RouteTransition";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";

// Agentic UPI Payments (Build Specs §E1) - the tenant-side console for the
// mandate model: grant a signed mandate to an agent, watch spend against its
// caps, revoke it, and audit the transactions it produced. All amounts cross
// the wire as integer paise; this page only converts for display/input.
//
// Scope gate: MANAGE_AGENTS is the UX gate only (same posture as the other
// /admin/* pages) - real enforcement stays in SecurityConfig/the controller.

function fmtTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "—";
}

function mandateStatusBadge(status: MandateStatus) {
  if (status === "ACTIVE") return <Badge variant="default">ACTIVE</Badge>;
  if (status === "REVOKED") return <Badge variant="destructive">REVOKED</Badge>;
  return <Badge variant="secondary">{status}</Badge>;
}

const TONE_BAR: Record<string, string> = {
  ok: "bg-emerald-500",
  warn: "bg-amber-500",
  down: "bg-red-500",
};

/** Spend-vs-cap meter for one mandate (§E1 acceptance surface). */
function SpendMeter({ spend, period }: { spend: MandateSpend | null; period: "daily" | "monthly" }) {
  const t = useTranslations("adminPaymentsPage");
  if (!spend) return null;
  const cap = period === "daily" ? spend.maxDailyPaise : spend.maxMonthlyPaise;
  const used = period === "daily" ? spend.dailySpendPaise : spend.monthlySpendPaise;
  const pct = cap > 0 ? Math.min(100, Math.round((used / cap) * 100)) : 0;
  const tone = spendTone(used, cap);
  return (
    <div className="min-w-44">
      <div className="flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
        <span>{period === "daily" ? t("spendDaily") : t("spendMonthly")}</span>
        <span className="font-mono">
          {fmtPaise(used)} / {fmtPaise(cap)}
        </span>
      </div>
      <div
        className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-secondary"
        role="progressbar"
        aria-label={period === "daily" ? t("spendDaily") : t("spendMonthly")}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
      >
        <div className={`h-full ${TONE_BAR[tone]}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

// ---- Grant wizard --------------------------------------------------------

interface DraftForm {
  scope: MandateScope;
  perTxn: string;
  daily: string;
  monthly: string;
  vpas: string;
  categories: string;
  policy: MandateApprovalPolicy;
  threshold: string;
  validFrom: string;
  validUntil: string;
}

const EMPTY_FORM: DraftForm = {
  scope: "BOTH",
  perTxn: "",
  daily: "",
  monthly: "",
  vpas: "",
  categories: "",
  policy: "ABOVE_THRESHOLD",
  threshold: "",
  validFrom: "",
  validUntil: "",
};

function csvList(raw: string): string[] | null {
  const items = raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  return items.length ? items : null;
}

function GrantWizard({ agentId, onGranted }: { agentId: string; onGranted: () => void }) {
  const t = useTranslations("adminPaymentsPage");
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<DraftForm>(EMPTY_FORM);
  const [preview, setPreview] = useState<MandatePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const requestBody = useMemo(() => {
    const maxPerTxnPaise = rupeesToPaise(form.perTxn);
    const maxDailyPaise = rupeesToPaise(form.daily);
    const maxMonthlyPaise = rupeesToPaise(form.monthly);
    if (maxPerTxnPaise == null || maxDailyPaise == null || maxMonthlyPaise == null) return null;
    const validFrom = form.validFrom ? new Date(form.validFrom) : new Date();
    const validUntil = form.validUntil ? new Date(form.validUntil) : null;
    if (!validUntil || Number.isNaN(validFrom.getTime()) || Number.isNaN(validUntil.getTime())) return null;
    const threshold = rupeesToPaise(form.threshold);
    return {
      agentId,
      scope: form.scope,
      maxPerTxnPaise,
      maxDailyPaise,
      maxMonthlyPaise,
      allowedVpas: csvList(form.vpas),
      allowedMerchantCategories: csvList(form.categories),
      approvalPolicy: form.policy,
      approvalThresholdPaise: form.policy === "ABOVE_THRESHOLD" ? threshold : null,
      validFrom: validFrom.toISOString(),
      validUntil: validUntil.toISOString(),
    };
  }, [agentId, form]);

  const handleReview = async () => {
    if (!requestBody) {
      setError(t("formInvalid"));
      return;
    }
    if (requestBody.approvalPolicy === "ABOVE_THRESHOLD" && requestBody.approvalThresholdPaise == null) {
      setError(t("thresholdRequired"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setPreview(await api.previewMandate(requestBody));
    } catch (e: any) {
      setError(e?.message || t("previewFailed"));
    } finally {
      setBusy(false);
    }
  };

  const handleGrant = async () => {
    if (!requestBody) return;
    setBusy(true);
    setError(null);
    try {
      await api.grantMandate(requestBody);
      toast.success(t("grantSuccess"));
      setOpen(false);
      setForm(EMPTY_FORM);
      setPreview(null);
      onGranted();
    } catch (e: any) {
      setError(e?.message || t("grantFailed"));
    } finally {
      setBusy(false);
    }
  };

  const field = (key: keyof DraftForm, label: string, props: any = {}) => (
    <div className="grid gap-1.5">
      <Label htmlFor={`mandate-${key}`}>{label}</Label>
      <Input
        id={`mandate-${key}`}
        value={form[key]}
        onChange={(e) => setForm((f) => ({ ...f, [key]: e.target.value }))}
        {...props}
      />
    </div>
  );

  if (!open) {
    return (
      <Button type="button" size="sm" onClick={() => setOpen(true)}>
        {t("grantMandate")}
      </Button>
    );
  }

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t("wizardTitle")}</h3>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => {
            setOpen(false);
            setPreview(null);
            setError(null);
          }}
        >
          {t("cancel")}
        </Button>
      </div>

      {!preview && (
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="grid gap-1.5">
            <Label htmlFor="mandate-scope">{t("fieldScope")}</Label>
            <Select
              id="mandate-scope"
              value={form.scope}
              onValueChange={(v) => setForm((f) => ({ ...f, scope: v as MandateScope }))}
              options={[
                { value: "COLLECT", label: t("scopeCollect") },
                { value: "PAY", label: t("scopePay") },
                { value: "BOTH", label: t("scopeBoth") },
              ]}
            />
          </div>
          {field("perTxn", t("fieldPerTxnCap"), { inputMode: "decimal", placeholder: "500.00" })}
          {field("daily", t("fieldDailyCap"), { inputMode: "decimal", placeholder: "5000.00" })}
          {field("monthly", t("fieldMonthlyCap"), { inputMode: "decimal", placeholder: "50000.00" })}
          {field("vpas", t("fieldVpas"), { placeholder: "vendor@bank, payroll@bank" })}
          {field("categories", t("fieldCategories"), { placeholder: "5411, 5812" })}
          <div className="grid gap-1.5">
            <Label htmlFor="mandate-policy">{t("fieldApprovalPolicy")}</Label>
            <Select
              id="mandate-policy"
              value={form.policy}
              onValueChange={(v) => setForm((f) => ({ ...f, policy: v as MandateApprovalPolicy }))}
              options={[
                { value: "ALWAYS", label: t("policyAlways") },
                { value: "ABOVE_THRESHOLD", label: t("policyAboveThreshold") },
                { value: "NEVER", label: t("policyNever") },
              ]}
            />
          </div>
          {form.policy === "ABOVE_THRESHOLD" &&
            field("threshold", t("fieldThreshold"), { inputMode: "decimal", placeholder: "1000.00" })}
          {field("validFrom", t("fieldValidFrom"), { type: "datetime-local" })}
          {field("validUntil", t("fieldValidUntil"), { type: "datetime-local" })}
        </div>
      )}

      {/* The "verify what you sign" step: the exact canonical JSON the
          mandate will be sealed as, plus its SHA-256 fingerprint. */}
      {preview && (
        <div className="grid gap-2">
          <Label>{t("canonicalReviewLabel")}</Label>
          <pre className="max-h-64 overflow-auto rounded-md border border-border bg-secondary/40 p-3 font-mono text-xs text-foreground">
            {preview.canonicalJson}
          </pre>
          <div className="text-xs text-muted-foreground">
            SHA-256: <code className="break-all font-mono">{preview.canonicalHashSha256}</code>
          </div>
          <p className="text-xs text-muted-foreground">{t("canonicalReviewHint")}</p>
        </div>
      )}

      {error && <div className="mt-3 text-sm text-destructive">{error}</div>}

      <div className="mt-4 flex gap-2">
        {!preview ? (
          <Button type="button" size="sm" disabled={busy || !requestBody} onClick={handleReview}>
            {t("reviewMandate")}
          </Button>
        ) : (
          <>
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => setPreview(null)}>
              {t("back")}
            </Button>
            <Button type="button" size="sm" disabled={busy} onClick={handleGrant}>
              {t("signAndGrant")}
            </Button>
          </>
        )}
      </div>
    </Card>
  );
}

// ---- Page ------------------------------------------------------------------

export default function AdminPaymentsPage() {
  const allowed = useRequireScope("MANAGE_AGENTS");
  const t = useTranslations("adminPaymentsPage");
  const tu = useTranslations("adminCommerceUi");
  const tl = useTranslations("adminLeftoversUi");

  const [agents, setAgents] = useState<any[]>([]);
  const [agentId, setAgentId] = useState<string>("");
  const [mandates, setMandates] = useState<PaymentMandate[]>([]);
  const [spends, setSpends] = useState<Record<string, MandateSpend>>({});
  const [revokeTarget, setRevokeTarget] = useState<PaymentMandate | null>(null);
  const [revokeReason, setRevokeReason] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dataLoading, setDataLoading] = useState(false);
  const [mandateFilter, setMandateFilter] = useState("");

  usePageReady(loaded);

  // Transactions page on the server (limit/offset + X-Total-Count); an older core is detected and
  // paged in the browser from the newest TXN_FETCH_LIMIT rows.
  const txns = usePagedList<PaymentTransaction>(
    allowed && agentId ? `/api/payments/transactions?agentId=${encodeURIComponent(agentId)}` : null,
    TXN_FETCH_LIMIT
  );
  const transactions = txns.rows;

  const mandateStatuses = useMemo(() => Array.from(new Set(mandates.map((m) => m.status))).sort(), [mandates]);
  const filteredMandates = useMemo(
    () => (mandateFilter ? mandates.filter((m) => m.status === mandateFilter) : mandates),
    [mandates, mandateFilter]
  );

  useEffect(() => {
    if (!allowed) return;
    api
      .listAgents()
      .then((rows: any) => {
        const list = Array.isArray(rows) ? rows : [];
        setAgents(list);
        if (list.length && !agentId) setAgentId(String(list[0].id));
      })
      .catch((e: any) => {
        setAgents([]);
        setError(e?.message || tu("agentsLoadFailed"));
      })
      .finally(() => setLoaded(true));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowed]);

  const loadForAgent = useCallback(
    async (id: string) => {
      if (!id) return;
      setError(null);
      setDataLoading(true);
      try {
        const mandateRows = await api.listMandates(id);
        const ms = Array.isArray(mandateRows) ? mandateRows : [];
        setMandates(ms);
        setSpends(await loadMandateSpends(id, ms));
      } catch (e: any) {
        setError(e?.message || t("loadFailed"));
      } finally {
        setDataLoading(false);
      }
    },
    [t]
  );

  useEffect(() => {
    if (allowed && agentId) void loadForAgent(agentId);
  }, [allowed, agentId, loadForAgent]);

  // Switching agent starts the transaction list from its first page again.
  const resetTxnPage = txns.resetPage;
  useEffect(() => {
    resetTxnPage();
  }, [agentId, resetTxnPage]);

  const handleRevoke = async () => {
    if (!revokeTarget) return;
    try {
      await api.revokeMandate(revokeTarget.id, revokeReason.trim() || undefined);
      toast.success(t("revokeSuccess"));
      setRevokeTarget(null);
      setRevokeReason("");
      void loadForAgent(agentId);
      void txns.reload(true);
    } catch (e: any) {
      toast.error(e?.message || t("revokeFailed"));
    }
  };

  if (!allowed) return null;

  return (
    <AppShell eyebrow={tl("eyebrowAdministration")}>
      <>
        <PageHeader title={t("pageTitle")} subtitle={t("pageSubtitle")} />

        <div className="mb-5 max-w-md">
          <Label htmlFor="payments-agent">{t("agentPickerLabel")}</Label>
          <Select
            id="payments-agent"
            value={agentId}
            onValueChange={setAgentId}
            placeholder={t("noAgents")}
            options={agents.map((a: any) => ({ value: String(a.id), label: a.name || a.id }))}
          />
        </div>

        <p className="mb-4 text-sm text-muted-foreground">
          {tu("relatedLabel")}:{" "}
          <Link href="/admin/commerce" className="text-primary underline">
            {tu("viewCommerce")}
          </Link>
          {" · "}
          <Link href="/admin/agent-trust" className="text-primary underline">
            {tu("viewAgentTrust")}
          </Link>
        </p>

        {error && (
          <ErrorRetry
            message={error}
            onRetry={() => {
              if (agentId) {
                void loadForAgent(agentId);
                void txns.reload();
              } else window.location.reload();
            }}
          />
        )}
        {txns.error && !error && <ErrorRetry message={txns.error} onRetry={() => void txns.reload()} />}

        {agentId && (dataLoading || txns.loading) && mandates.length === 0 && transactions.length === 0 && (
          <ListSkeleton />
        )}

        <Card className="mb-6 p-4">
          <div className="mb-3 flex items-center justify-between">
            <h2 className="text-sm font-semibold">{t("mandatesHeading")}</h2>
            {agentId && <GrantWizard agentId={agentId} onGranted={() => loadForAgent(agentId)} />}
          </div>
          {mandates.length === 0 ? (
            <EmptyState title={t("noMandatesTitle")} description={t("noMandatesHint")} />
          ) : (
            <>
              <div className="mb-3">
                <FilterPills options={mandateStatuses} value={mandateFilter} onChange={setMandateFilter} />
              </div>
              {filteredMandates.length === 0 ? (
                <p className="text-sm text-muted-foreground">{tu("noMatches")}</p>
              ) : (
                <PagedList
                  items={filteredMandates}
                  itemLabel={tu("itemMandates")}
                  className="grid gap-3"
                  renderItem={(m) => (
                    <div key={m.id} className="rounded-lg border border-border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        {mandateStatusBadge(m.status)}
                        <Badge variant="outline">{m.scope}</Badge>
                        <span className="text-xs text-muted-foreground">
                          {t("capsRow", {
                            perTxn: fmtPaise(m.maxPerTxnPaise),
                            daily: fmtPaise(m.maxDailyPaise),
                            monthly: fmtPaise(m.maxMonthlyPaise),
                          })}
                        </span>
                        <span className="ms-auto text-xs text-muted-foreground">
                          {t("validityRow", { from: fmtTime(m.validFrom), until: fmtTime(m.validUntil) })}
                        </span>
                        {m.status === "ACTIVE" && (
                          <Button
                            type="button"
                            variant="destructive"
                            size="sm"
                            onClick={() => {
                              setRevokeTarget(m);
                              setRevokeReason("");
                            }}
                          >
                            {t("revoke")}
                          </Button>
                        )}
                      </div>
                      {m.status === "ACTIVE" && spends[m.id] && (
                        <div className="mt-3 flex flex-wrap gap-6">
                          <SpendMeter spend={spends[m.id]} period="daily" />
                          <SpendMeter spend={spends[m.id]} period="monthly" />
                        </div>
                      )}
                      <div className="mt-2 text-xs text-muted-foreground">
                        {t("mandateHashPrefix")} <code className="break-all font-mono">{m.canonicalHashSha256}</code>
                      </div>
                      {m.revokeReason && (
                        <div className="mt-1 text-xs text-muted-foreground">
                          {t("revokedReasonRow", { reason: m.revokeReason })}
                        </div>
                      )}
                    </div>
                  )}
                />
              )}
            </>
          )}
        </Card>

        <Card className="p-4">
          <h2 className="mb-3 text-sm font-semibold">{t("transactionsHeading")}</h2>
          {transactions.length === 0 ? (
            txns.loading ? null : (
              <EmptyState title={t("noTransactionsTitle")} description={t("noTransactionsHint")} />
            )
          ) : (
            <TransactionsTable
              transactions={transactions}
              paging={{
                page: txns.page,
                pageSize: txns.pageSize,
                total: txns.total,
                hasMore: txns.hasMore,
                loading: txns.loading,
                onPageChange: txns.setPage,
                onPageSizeChange: txns.setPageSize,
              }}
            />
          )}
        </Card>

        <AlertDialog open={!!revokeTarget} onOpenChange={(o) => !o && setRevokeTarget(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{t("revokeDialogTitle")}</AlertDialogTitle>
              <AlertDialogDescription>{t("revokeDialogBody")}</AlertDialogDescription>
            </AlertDialogHeader>
            <Textarea
              value={revokeReason}
              onChange={(e) => setRevokeReason(e.target.value)}
              placeholder={t("revokeReasonPlaceholder")}
              maxLength={500}
            />
            <AlertDialogFooter>
              <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
              <AlertDialogAction onClick={handleRevoke}>{t("revokeConfirm")}</AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </>
    </AppShell>
  );
}
