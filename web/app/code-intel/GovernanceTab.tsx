// @ts-nocheck
"use client";

import { useCallback, useEffect, useState } from "react";
import { RefreshCw, ShieldCheck, AlertTriangle } from "lucide-react";

import Select from "../components/Select";
import { Button } from "../components/ui/button";

import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";

import { useTranslations } from "../lib/i18n";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { codeIntel } from "./lib";
import { Toggle, Field } from "./shared";
// The audit endpoint returns only the newest N events; the pager must say so.
const AUDIT_LIMIT = 60;

export default function GovernanceTab({ onError }) {
    const tHC = useTranslations("hcCodeintelGovernanceTab");
  const t = useTranslations("codeIntel");
  const tg = useTranslations("codeIntelGov");
  const [policy, setPolicy] = useState(null);
  const [policyError, setPolicyError] = useState(null);
  const [approvals, setApprovals] = useState([]);
  const [trail, setTrail] = useState([]);
  const [approvalsError, setApprovalsError] = useState(null);
  const [trailError, setTrailError] = useState(null);
  const [busy, setBusy] = useState(false);
  const approvalsPagination = usePagination(approvals);
  const trailPagination = usePagination(trail);

  const refresh = useCallback(() => {
    setPolicyError(null);
    codeIntel
      .getPolicy()
      .then((p) => {
        setPolicy(p);
        setPolicyError(null);
      })
      .catch((e) => {
        setPolicy(null);
        setPolicyError(e.message);
      });
    setApprovalsError(null);
    setTrailError(null);
    // A failed load is an error state with Retry, never "nothing waiting": the
    // last good rows stay on screen so a transient poll failure does not blank them.
    codeIntel
      .listApprovals()
      .then(setApprovals)
      .catch((e) => setApprovalsError(e?.message || String(e)));
    codeIntel
      .audit(AUDIT_LIMIT)
      .then((tr) => setTrail(tr.slice().reverse()))
      .catch((e) => setTrailError(e?.message || String(e)));
  }, []);

  useEffect(() => {
    refresh();
    // 2026-08-15 fix: this used to be a one-shot, mount-only fetch, so a
    // pending approval created (e.g. by a run() call from the Execute tab)
    // while this tab was already mounted never appeared here without a full
    // page reload - confirmed live: an approval existed in code-intel's own
    // queue (GET /approvals returned it directly) while this panel still
    // said "Nothing waiting on a human right now." Approvals are exactly
    // the kind of state a human is actively waiting to act on, so silent
    // staleness here is worse than the extra polling cost - 5s is arbitrary
    // (no existing precedent on this page to match), chosen as short enough
    // to feel live without hammering three endpoints every render.
    const interval = setInterval(refresh, 5000);
    return () => clearInterval(interval);
  }, [refresh]);

  const savePolicy = async (patch) => {
    setBusy(true);
    try {
      setPolicy(await codeIntel.setPolicy(patch));
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy(false);
    }
  };

  const resolve = async (id, approved) => {
    try {
      await codeIntel.resolveApproval(id, approved);
      refresh();
    } catch (e) {
      onError(e.message);
    }
  };

  const modeHelp = {
    read_only: t("modeHelpReadOnly"),
    test_gated: t("modeHelpTestGated"),
    autopilot: t("modeHelpAutopilot"),
  };

  const auditKindCls = (k) => {
    const s = k || "";
    const tone = s.includes("denied")
      ? "text-destructive"
      : s.includes("executed")
        ? "text-[var(--state-success)]"
        : s.includes("escalated")
          ? "text-[var(--state-warning)]"
          : "text-muted-foreground";
    return `min-w-[130px] rounded-full border border-border bg-muted px-2 py-0.5 text-center font-mono text-[11px] ${tone}`;
  };

  return (
    <div className="space-y-4">
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <ShieldCheck size={16} className="text-primary" /> Policy
            </CardTitle>
          </CardHeader>
          <CardContent>
            {policyError ? (
              <div className="text-sm text-muted-foreground">
                <p className="mb-2 text-destructive">{tHC("s6")} {policyError}</p>
                <p>
                  {tHC("s5")}
                </p>
                <Button variant="secondary" size="sm" className="mt-2" onClick={refresh}>
                  <RefreshCw size={13} /> {t("retryButton")}
                </Button>
              </div>
            ) : !policy ? (
              <p className="text-sm text-muted-foreground">{tHC("s4")}</p>
            ) : (
              <>
                <Field label={t("autonomyModeLabel")}>
                  <Select
                    value={policy.mode}
                    disabled={busy}
                    onChange={(e) => savePolicy({ mode: e.target.value })}
                    options={[
                      { value: "read_only", label: t("modeOptionReadOnly") },
                      { value: "test_gated", label: t("modeOptionTestGated") },
                      { value: "autopilot", label: t("modeOptionAutopilot") },
                    ]}
                  />
                </Field>
                <p className="text-sm text-muted-foreground">{modeHelp[policy.mode]}</p>
                <div className="mt-3 flex flex-wrap gap-3.5">
                  <Toggle
                    label={t("executionEnabledLabel")}
                    on={policy.exec_enabled}
                    disabled={busy}
                    onChange={(v) => savePolicy({ exec_enabled: v })}
                  />
                  <Toggle
                    label={t("fileWritesEnabledLabel")}
                    on={policy.apply_enabled}
                    disabled={busy}
                    onChange={(v) => savePolicy({ apply_enabled: v })}
                  />
                </div>
                <div className="mt-3 text-xs leading-relaxed text-muted-foreground">
                  caps: {policy.max_files_per_apply} {tHC("s3")} {Math.round(policy.max_bytes_per_file / 1024)} {tHC("s2")} {policy.max_command_timeout}{tHC("s1")}
                  <br />
                  protected: {(policy.protected_path_globs || []).join(", ")}
                </div>
              </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              {t("pendingApprovalsHeading", { count: approvals.length })}
              <Button variant="ghost" size="sm" className="ms-auto" onClick={refresh}>
                <RefreshCw size={13} /> {t("refreshButton")}
              </Button>
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            {approvalsError && (
              <LoadError
                message={tg("approvalsLoadFailed", { error: approvalsError })}
                retryLabel={t("retryButton")}
                onRetry={refresh}
              />
            )}
            {!approvalsError && approvals.length === 0 && (
              <p className="text-sm text-muted-foreground">{t("nothingAwaitingApproval")}</p>
            )}
            {approvalsPagination.pageItems.map((a) => (
              <div key={a.approval_id} className="flex items-center gap-2 rounded-lg border border-border p-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-semibold text-foreground">
                    {a.request.kind === "run"
                      ? `run: ${a.request.command}`
                      : `apply: ${(a.request.files || []).map((f) => f.path).join(", ")}`}
                  </div>
                  <div className="mt-0.5 text-xs text-muted-foreground">{a.request.policy_reason}</div>
                </div>
                <Button size="sm" onClick={() => resolve(a.approval_id, true)}>
                  {t("approveButton")}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                  onClick={() => resolve(a.approval_id, false)}
                >
                  {t("denyButton")}
                </Button>
              </div>
            ))}
            <PaginationControls
              page={approvalsPagination.page}
              totalPages={approvalsPagination.totalPages}
              totalItems={approvalsPagination.totalItems}
              pageSize={approvalsPagination.pageSize}
              onPageChange={approvalsPagination.setPage}
              onPageSizeChange={approvalsPagination.setPageSize}
              itemLabel="requests"
            />
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-sm">
            {t("auditTrailHeading")}
            <Button variant="ghost" size="sm" className="ms-auto" onClick={refresh}>
              <RefreshCw size={13} /> {t("refreshButton")}
            </Button>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {trailError && (
            <LoadError
              message={tg("auditLoadFailed", { error: trailError })}
              retryLabel={t("retryButton")}
              onRetry={refresh}
            />
          )}
          {!trailError && trail.length === 0 && <p className="text-sm text-muted-foreground">{t("noActivityYet")}</p>}
          {trail.length >= AUDIT_LIMIT && (
            <p className="mb-2 text-xs text-muted-foreground">{tg("auditCapNotice", { count: AUDIT_LIMIT })}</p>
          )}
          <div>
            {trailPagination.pageItems.map((e, i) => (
              <div key={i} className="flex items-center gap-2.5 border-b border-border py-2 last:border-0">
                <span className={auditKindCls(e.kind)}>{e.kind}</span>
                <span className="flex-1 text-xs text-foreground">
                  {e.command || (e.written && `wrote ${e.written.join(", ")}`) || e.decision || e.reason || ""}
                  {e.canary && e.canary.length > 0 && (
                    <span className="ms-1 inline-flex items-center gap-1 text-destructive">
                      <AlertTriangle size={12} aria-hidden="true" /> canary
                    </span>
                  )}
                </span>
                {e.actor && <span className="text-xs text-muted-foreground">{e.actor}</span>}
              </div>
            ))}
          </div>
          <PaginationControls
            page={trailPagination.page}
            totalPages={trailPagination.totalPages}
            totalItems={trailPagination.totalItems}
            pageSize={trailPagination.pageSize}
            onPageChange={trailPagination.setPage}
            onPageSizeChange={trailPagination.setPageSize}
            itemLabel="events"
          />
        </CardContent>
      </Card>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Shared bits                                                         */

function LoadError({ message, retryLabel, onRetry }) {
  return (
    <div role="alert" className="rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
      <p className="mb-2">{message}</p>
      <Button variant="secondary" size="sm" onClick={onRetry}>
        <RefreshCw size={13} /> {retryLabel}
      </Button>
    </div>
  );
}
