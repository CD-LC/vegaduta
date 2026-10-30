"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Power, AlertOctagon, Gift, Send, CheckCircle2 } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Alert, AlertDescription } from "../ui/alert";
import { timeAgo, SectionCard, SwitchToggle } from "./common";
import { useSectionData } from "./observability";
import TemplateReasonDialog from "../templates/TemplateReasonDialog";
import { useConfirm } from "../admin/ConfirmDialog";
import { useTranslations } from "../../../lib/i18n";

// Platform-level emergency + bulk-action controls, all backed by live
// /api/platform/** endpoints (PlatformController + TenantEmergencyControlService
// + SiteWidePromoService). The kill switch is the platform's "big red button":
// stopping it fails every tenant request closed at TenantContextFilter while
// deliberately exempting ROLE_platform-admin (see that filter's javadoc) - so
// this console stays reachable and this same card is where the platform gets
// resumed. Every action is audited server-side with the operator's subject.

// ── Platform-wide kill switch ───────────────────────────────────────────────
export function KillSwitchSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsPlatformcontrols");
  const askConfirm = useConfirm();
  const { data: status, error, loading, refresh } = useSectionData<any>(() => api.getKillSwitchStatus(), null, pollMs);
  const [stopOpen, setStopOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  const stopped = !!status?.stopped;

  async function handleStop(reason: string) {
    setBusy(true);
    try {
      await api.stopPlatform(reason);
      toast.error("Platform is now STOPPED - every tenant request is being rejected.", { duration: 9000 });
      setStopOpen(false);
      refresh().catch(() => {});
    } catch (err: any) {
      toast.error(err.message || "Could not stop the platform.");
    } finally {
      setBusy(false);
    }
  }

  async function handleResume() {
    if (!(await askConfirm("Resume the platform? Every tenant's requests will flow again immediately."))) return;
    setBusy(true);
    try {
      await api.resumePlatform();
      toast.success("Platform resumed - tenant traffic is flowing again.");
      refresh().catch(() => {});
    } catch (err: any) {
      toast.error(err.message || "Could not resume the platform.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard
      title={tHC("s15")}
      subtitle="Instantly rejects every authenticated tenant request and every channel webhook chat - reversible, audited, and platform-admin sessions stay exempt so you can always get back in here to resume"
      icon={Power}
      delay={delay}
      actions={
        stopped ? (
          <Badge variant="outline" className="border-destructive/40 bg-destructive/10 text-destructive">
            {tHC("s14")}
          </Badge>
        ) : (
          <Badge
            variant="outline"
            className="border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
          >
            RUNNING
          </Badge>
        )
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("s13")}</p>
      ) : (
        <div className="space-y-3">
          {stopped && (
            <Alert variant="destructive">
              <AlertOctagon />
              <AlertDescription>
                {tHC("s12")} {timeAgo(status?.stoppedAt)}
                {status?.updatedBy ? ` by ${status.updatedBy}` : ""}
                {status?.reason ? ` — "${status.reason}"` : ""}{tHC("s11")}
              </AlertDescription>
            </Alert>
          )}
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border px-4 py-3">
            <div className="min-w-0">
              <div className="text-sm font-medium text-foreground">
                {stopped ? "All tenant traffic is blocked" : "All tenant traffic is flowing normally"}
              </div>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {stopped
                  ? "Users see sign-ins and API calls fail; their data is untouched. Resume when the incident is over."
                  : "Use only for a platform-wide incident (outage containment, abuse flood, data-integrity pause). Per-tenant stops live on Tenants & Users."}
              </p>
            </div>
            {stopped ? (
              <Button size="sm" onClick={handleResume} disabled={busy}>
                <CheckCircle2 size={14} /> {busy ? "Resuming…" : "Resume platform"}
              </Button>
            ) : (
              <Button variant="destructive" size="sm" onClick={() => setStopOpen(true)} disabled={busy}>
                <Power size={14} /> {tHC("s10")}
              </Button>
            )}
          </div>
        </div>
      )}

      <TemplateReasonDialog
        open={stopOpen}
        title={tHC("s9")}
        description="Every tenant's sign-ins, API calls and channel webhooks will be rejected immediately. Platform-admin sessions (this console) are exempt so you can resume from this same card. A reason is required - it goes into the audit log."
        confirmLabel="Stop platform"
        submitting={busy}
        required
        onCancel={() => setStopOpen(false)}
        onConfirm={handleStop}
      />
    </SectionCard>
  );
}

// ── Site-wide promo grant ───────────────────────────────────────────────────
// POST /api/platform/promos/unlimited-14d - grants UNLIMITED to every ACTIVE
// tenant for N days, auto-reverting via CompGrantExpiryReverter. Idempotent:
// tenants already on UNLIMITED are skipped. sendEmail is a deliberate second
// opt-in, matching the backend's own split of "grant" vs "mail everyone".
export function SiteWidePromoSection({ delay }: { delay?: number }) {
    const tHC = useTranslations("hcPlatformadminComponentsSectionsPlatformcontrols");
  const askConfirm = useConfirm();
  const [days, setDays] = useState("14");
  const [reason, setReason] = useState("");
  const [sendEmail, setSendEmail] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [promoFieldErrors, setPromoFieldErrors] = useState<Record<string, string>>({});

  async function handleGrant() {
    const durationDays = Number(days);
    const errors: Record<string, string> = {};
    if (!Number.isInteger(durationDays) || durationDays < 1 || durationDays > 365) {
      errors.days = "Must be a whole number of days between 1 and 365.";
    }
    if (!reason.trim()) {
      errors.reason = "A reason is required - it is recorded against every grant.";
    }
    setPromoFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      document.getElementById(errors.days ? "promo-days" : "promo-reason")?.focus();
      toast.error("Fix the highlighted field(s) below.");
      return;
    }
    const summary = `Grant UNLIMITED to every active tenant for ${durationDays} days${sendEmail ? " AND email every tenant admin" : ""}?`;
    if (!(await askConfirm(summary))) return;
    setBusy(true);
    setResult(null);
    try {
      const res = await api.grantSiteWidePromo({
        durationDays,
        reason: reason.trim(),
        sendEmail,
      });
      setResult(res);
      setPromoFieldErrors({});
      toast.success(
        `Promo granted to ${res?.grantedTenants?.length ?? 0} tenant${res?.grantedTenants?.length === 1 ? "" : "s"}.`
      );
    } catch (err: any) {
      toast.error(err.message || "Could not run the promo grant.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard
      title={tHC("s8")}
      subtitle="One-time bulk grant of UNLIMITED to every active tenant, auto-reverting when the window ends. Idempotent - tenants already on UNLIMITED are skipped."
      icon={Gift}
      delay={delay}
    >
      <div className="grid gap-3 sm:grid-cols-[8rem_1fr]">
        <div className="space-y-1.5">
          <Label htmlFor="promo-days" className="text-xs text-muted-foreground">
            {tHC("s7")}
          </Label>
          <Input
            id="promo-days"
            type="number"
            min="1"
            max="365"
            value={days}
            onChange={(e) => setDays(e.target.value)}
            aria-invalid={!!promoFieldErrors.days}
          />
          {promoFieldErrors.days && <p className="text-xs text-destructive">{promoFieldErrors.days}</p>}
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="promo-reason" className="text-xs text-muted-foreground">
            {tHC("s6")}
          </Label>
          <Input
            id="promo-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder={tHC("s5")}
            aria-invalid={!!promoFieldErrors.reason}
          />
          {promoFieldErrors.reason && <p className="text-xs text-destructive">{promoFieldErrors.reason}</p>}
        </div>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3 pt-1">
        <label className="flex items-center gap-2 text-xs text-muted-foreground">
          <SwitchToggle on={sendEmail} onClick={() => setSendEmail((v) => !v)} label={tHC("s4")} />
          <span className="inline-flex items-center gap-1">
            <Send size={12} /> {tHC("s3")}
          </span>
        </label>
        <Button size="sm" onClick={handleGrant} disabled={busy}>
          <Gift size={14} /> {busy ? "Granting…" : "Grant promo"}
        </Button>
      </div>

      <AnimatePresence initial={false}>
        {result && (
          <motion.div
            className="rounded-lg border border-[var(--success-border)] bg-[var(--success-bg)] p-4"
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
          >
            <div className="flex flex-wrap items-center gap-2 text-sm font-medium text-foreground">
              <CheckCircle2 size={15} className="text-[var(--state-success)]" />
              {tHC("s2")} {result.grantedTenants?.length ?? 0} tenant{result.grantedTenants?.length === 1 ? "" : "s"}
              {result.skippedAlreadyUnlimitedTenantIds?.length > 0 && (
                <span className="text-xs font-normal text-muted-foreground">
                  ({result.skippedAlreadyUnlimitedTenantIds.length} {tHC("s1")}
                </span>
              )}
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              Granted {result.grantedAt ? new Date(result.grantedAt).toLocaleString() : ""}
              {result.expiresAt ? ` · auto-reverts ${new Date(result.expiresAt).toLocaleString()}` : ""}
            </p>
            {result.grantedTenants?.length > 0 && (
              <p className="mt-2 break-words font-mono text-[11px] text-muted-foreground">
                {result.grantedTenants.map((t: any) => t.name).join(", ")}
              </p>
            )}
          </motion.div>
        )}
      </AnimatePresence>
    </SectionCard>
  );
}
