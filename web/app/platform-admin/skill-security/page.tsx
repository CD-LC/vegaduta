"use client";

import { useCallback, useEffect, useState } from "react";
import { ShieldAlert, RefreshCw, FileSearch, CheckCircle2 } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, InfoNote } from "../components/sections/common";
import { ToneChip, UnavailableNote } from "../components/ops/OpsWidgets";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import {
  skillSecurityApi,
  type SkillReviewItem,
  type SkillSecurityOverview,
  type SkillSecurityReport,
} from "../lib/adminApi/skillSecurity";
import { toast } from "../lib/toastBus";
import { UNAVAILABLE, formatExact, formatRelative } from "../lib/platformFormat";
import { reviewReasonLabel, riskMeta } from "../lib/opsFormat";
import { useConfirm } from "../components/admin/ConfirmDialog";
import {} from "../components/admin/AdminKit";
import { Checkbox } from "../../components/ui/checkbox";
import { useTranslations } from "../../lib/i18n";

// Skill supply-chain review (core /api/skill-security/**). Custom skills are
// third-party text that lands in an agent's prompt; core statically scans each
// one and pins its content hash when an owner approves it. This page is the
// platform-admin view of that queue: what needs review, the scan findings, and
// an approve action that records the exact hash the reviewer saw (core refuses
// with 409 if the skill changed in between).
//
// Scope caveat, stated on the page: the controller takes its tenant from the
// caller's own token, so this reviews the skills of the tenant this operator
// account belongs to. A cross-tenant queue needs a platform endpoint in core.

function ReportPanel({ name, onApproved }: { name: string; onApproved: () => void }) {
    const tHC = useTranslations("hcPlatformadminSkillsecurityPage");
  const askConfirm = useConfirm();
  const [report, setReport] = useState<SkillSecurityReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ack, setAck] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setReport(await skillSecurityApi.report(name));
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not load the scan report.");
    }
  }, [name]);

  useEffect(() => {
    setReport(null);
    setAck(false);
    void load();
  }, [load]);

  async function approve() {
    if (!report) return;
    const high = report.scan?.riskLevel === "high";
    if (high && !ack) return;
    if (
      !(await askConfirm(
        `Approve ${report.name} at hash ${report.contentSha256.slice(0, 12)}…? Agents can then load it.`
      ))
    )
      return;
    setBusy(true);
    try {
      const next = await skillSecurityApi.approve(report.name, report.contentSha256, high && ack);
      setReport(next);
      toast.success(`${report.name} approved.`);
      onApproved();
    } catch (err: any) {
      if (err?.status === 409) void load();
      // toJson already toasted the reason.
    } finally {
      setBusy(false);
    }
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{error}</AlertDescription>
      </Alert>
    );
  }
  if (!report) return <Skeleton className="h-40 w-full" />;

  const risk = riskMeta(report.scan?.riskLevel);
  const high = report.scan?.riskLevel === "high";
  const canApprove = report.source === "CUSTOM" && report.status !== "APPROVED";

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-semibold">{report.name}</span>
        <Badge variant="outline">{report.source === "BUILT_IN" ? "Built-in" : "Custom"}</Badge>
        <ToneChip tone={risk.tone} label={risk.label} />
        <Badge variant="secondary">{report.status}</Badge>
        {report.blocked && <ToneChip tone="bad" label={tHC("s20")} />}
      </div>

      <dl className="grid gap-x-6 gap-y-1 text-xs sm:grid-cols-2">
        <div>
          <dt className="inline text-muted-foreground">{tHC("s19")} </dt>
          <dd className="inline break-all font-mono">{report.contentSha256 || UNAVAILABLE}</dd>
        </div>
        <div>
          <dt className="inline text-muted-foreground">{tHC("s18")} </dt>
          <dd className="inline break-all font-mono">{report.approvedSha256 || "never approved"}</dd>
        </div>
        <div>
          <dt className="inline text-muted-foreground">{tHC("s17")} </dt>
          <dd className="inline">
            {report.approvedBy || UNAVAILABLE}
            {report.approvedAt && (
              <span title={formatExact(report.approvedAt)}> · {formatRelative(report.approvedAt)}</span>
            )}
          </dd>
        </div>
        <div>
          <dt className="inline text-muted-foreground">{tHC("s16")} </dt>
          <dd className="inline">{report.attachedAgentIds?.length ?? 0} {tHC("s15")}</dd>
        </div>
      </dl>

      {report.changes?.length > 0 && (
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{tHC("s14")}</p>
          <ul className="mt-1 list-disc space-y-0.5 ps-5 text-xs">
            {report.changes.map((c) => (
              <li key={c}>{c}</li>
            ))}
          </ul>
        </div>
      )}

      <div>
        <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
          {tHC("s13")}{report.scan?.findings?.length ?? 0}){report.scan?.truncated ? " - list truncated" : ""}
        </p>
        {!report.scan?.findings?.length ? (
          <p className="mt-1 text-xs text-muted-foreground">
            {tHC("s12")}
          </p>
        ) : (
          <div className="mt-1 space-y-1.5">
            {report.scan.findings.map((f, i) => {
              const m = riskMeta(f.severity);
              return (
                <div key={`${f.rule}-${i}`} className="rounded-md border border-border p-2 text-xs">
                  <div className="flex flex-wrap items-center gap-2">
                    <ToneChip tone={m.tone} label={f.severity} />
                    <span className="font-medium">{f.rule}</span>
                    <span className="font-mono text-[11px] text-muted-foreground">{f.where}</span>
                  </div>
                  <pre className="mt-1 whitespace-pre-wrap break-all font-mono text-[11px] text-muted-foreground">
                    {f.excerpt}
                  </pre>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {report.stagedScan && (
        <p className="text-xs text-[var(--warning-text)]">
          {tHC("s11")}{" "}
          {riskMeta(report.stagedScan.riskLevel).label.toLowerCase()}.
        </p>
      )}

      {report.disclaimer && <p className="text-[11px] text-muted-foreground">{report.disclaimer}</p>}

      {canApprove && (
        <div className="space-y-2 border-t border-border pt-3">
          {high && (
            <div className="flex items-start gap-2 text-xs">
              <Checkbox checked={ack} className="mt-0.5" onCheckedChange={(v) => setAck(v)} id="skill-ack" />
              <label htmlFor="skill-ack">
                {tHC("s10")}
              </label>
            </div>
          )}
          <Button size="sm" disabled={busy || (high && !ack)} onClick={approve}>
            <CheckCircle2 /> {busy ? "Approving..." : "Approve this version"}
          </Button>
        </div>
      )}
    </div>
  );
}

export default function SkillSecurityPage() {
    const tHC = useTranslations("hcPlatformadminSkillsecurityPage");
  const [overview, setOverview] = useState<SkillSecurityOverview | null>(null);
  const [reviews, setReviews] = useState<SkillReviewItem[] | null>(null);
  const [state, setState] = useState<"loading" | "ok" | "off" | "error">("loading");
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [o, r] = await Promise.all([skillSecurityApi.summary(), skillSecurityApi.reviews()]);
      setOverview(o);
      setReviews(Array.isArray(r) ? r : []);
      setState(o && o.enabled === false ? "off" : "ok");
      setErrorMsg(null);
    } catch (e: any) {
      // 404 = supply-chain scanning switched off server-side.
      if (e?.status === 404) {
        setState("off");
      } else {
        setState("error");
        setErrorMsg(e?.message || "Could not load skill security data.");
      }
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const flagged = (overview?.skills || []).filter(
    (s) => s.riskLevel === "high" || s.riskLevel === "medium" || s.status === "NEEDS_REVIEW"
  );
  const reviewPg = usePagination(reviews ?? [], 10);
  const flaggedPg = usePagination(flagged, 10);

  return (
    <AdminShell
      title={tHC("s9")}
      actions={
        <Button variant="outline" size="sm" onClick={() => void load()}>
          <RefreshCw /> Refresh
        </Button>
      }
    >
      <PageHint>
        {tHC("s8")}
      </PageHint>
      <InfoNote>
        {tHC("s7")}
      </InfoNote>

      {state === "loading" && <Skeleton className="h-32 w-full" />}
      {state === "off" && (
        <UnavailableNote
          title={tHC("s6")}
          reason="app.skills.supply-chain.enabled is false on this core, so there is nothing to review."
        />
      )}
      {state === "error" && (
        <Alert variant="destructive">
          <AlertDescription>{errorMsg}</AlertDescription>
        </Alert>
      )}

      {state === "ok" && (
        <>
          <div className="mb-4 flex flex-wrap gap-2 text-xs">
            <ToneChip
              tone={overview?.enforcing ? "ok" : "warn"}
              label={
                overview?.enforcing ? "Enforcing: unapproved skills are blocked" : "Not enforcing: scan + pin only"
              }
            />
            <ToneChip tone="muted" label={`${overview?.skills?.length ?? 0} skills scanned`} />
          </div>

          <SectionCard
            title={tHC("s5")}
            icon={ShieldAlert}
            subtitle={`${reviews?.length ?? 0} skill(s) waiting on an owner`}
          >
            {reviews && reviews.length === 0 && (
              <p className="text-sm text-muted-foreground">{tHC("s4")}</p>
            )}
            {reviewPg.pageItems.map((r) => {
              const m = riskMeta(r.riskLevel);
              return (
                <button
                  key={r.skillId}
                  type="button"
                  onClick={() => setSelected(selected === r.name ? null : r.name)}
                  className={`flex w-full flex-wrap items-center justify-between gap-2 rounded-lg border p-3 text-start text-sm transition-colors hover:bg-accent ${
                    selected === r.name ? "border-primary/50 bg-accent/50" : "border-border"
                  }`}
                >
                  <span className="min-w-0">
                    <span className="font-medium">{r.name}</span>
                    <span className="ms-2 text-xs text-muted-foreground">{reviewReasonLabel(r.reason)}</span>
                    {!r.enabled && <span className="ms-2 text-xs text-muted-foreground">(disabled)</span>}
                  </span>
                  <span className="flex items-center gap-2">
                    <ToneChip tone={m.tone} label={m.label} />
                    {r.approvedAt && (
                      <span className="text-[11px] text-muted-foreground" title={formatExact(r.approvedAt)}>
                        {tHC("s3")} {formatRelative(r.approvedAt)}
                      </span>
                    )}
                  </span>
                </button>
              );
            })}
            <PaginationControls
              page={reviewPg.page}
              totalPages={reviewPg.totalPages}
              totalItems={reviewPg.totalItems}
              pageSize={reviewPg.pageSize}
              onPageChange={reviewPg.setPage}
              onPageSizeChange={reviewPg.setPageSize}
              itemLabel="reviews"
            />
          </SectionCard>

          {flagged.length > 0 && (
            <SectionCard
              title={tHC("s2")}
              icon={FileSearch}
              subtitle="Every skill scanning medium or high, or needing review"
            >
              {flaggedPg.pageItems.map((s) => {
                const m = riskMeta(s.riskLevel);
                return (
                  <button
                    key={s.name}
                    type="button"
                    onClick={() => setSelected(selected === s.name ? null : s.name)}
                    className={`flex w-full flex-wrap items-center justify-between gap-2 rounded-lg border p-3 text-start text-sm transition-colors hover:bg-accent ${
                      selected === s.name ? "border-primary/50 bg-accent/50" : "border-border"
                    }`}
                  >
                    <span className="font-medium">{s.name}</span>
                    <span className="flex items-center gap-2 text-xs">
                      <span className="text-muted-foreground">{s.findingCount} finding(s)</span>
                      <ToneChip tone={m.tone} label={m.label} />
                      {s.blocked && <ToneChip tone="bad" label="Blocked" />}
                    </span>
                  </button>
                );
              })}
              <PaginationControls
                page={flaggedPg.page}
                totalPages={flaggedPg.totalPages}
                totalItems={flaggedPg.totalItems}
                pageSize={flaggedPg.pageSize}
                onPageChange={flaggedPg.setPage}
                onPageSizeChange={flaggedPg.setPageSize}
                itemLabel="skills"
              />
            </SectionCard>
          )}

          {selected && (
            <SectionCard title={tHC("s1")} icon={FileSearch}>
              <ReportPanel name={selected} onApproved={() => void load()} />
            </SectionCard>
          )}
        </>
      )}
    </AdminShell>
  );
}
