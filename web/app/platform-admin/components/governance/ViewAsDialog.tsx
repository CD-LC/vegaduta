"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { Eye, X, ShieldAlert } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import { useDialogFocus } from "../../lib/useDialogFocus";
import { toast } from "../../lib/toastBus";
import { governanceApi } from "../../lib/adminApi/governance";
import { IMPERSONATION_REASON_MIN, isSafeViewUrl, reasonProblem } from "../../lib/governanceFormat";
import { useTranslations } from "../../../lib/i18n";

// Read-only "view as" start dialog (POST /api/platform/impersonation). Needs a
// written reason (>= 10 chars, recorded and shown to the tenant admin in their
// audit log) and the platform master key, which is sent once in X-Master-Key
// and never stored. On success the tenant app's /view-as link (grant in the
// URL fragment) opens in a NEW tab with noopener, so this console tab and the
// viewed session never share a window reference.

export type ViewAsTarget = {
  tenantId: string;
  userId: string;
  label: string;
  tenantName?: string | null;
};

export default function ViewAsDialog({ target, onClose }: { target: ViewAsTarget | null; onClose: () => void }) {
  const tHC = useTranslations("hcPlatformadminComponentsGovernanceViewAsDialog");
  const open = target != null;
  const [reason, setReason] = useState("");
  const [masterKey, setMasterKey] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  // Set once the session exists: the fallback link for a blocked pop-up (a
  // window.open after an await has lost the click's user activation).
  const [started, setStarted] = useState<{ viewUrl: string; who: string } | null>(null);
  const [mounted, setMounted] = useState(false);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  useDialogFocus(open, dialogRef);

  useEffect(() => setMounted(true), []);
  useEffect(() => {
    if (open) {
      setReason("");
      setMasterKey("");
      setError(null);
      setTouched(false);
      setStarted(null);
    }
  }, [open]);
  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !submitting) onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, submitting, onClose]);

  if (!mounted) return null;

  const problem = reasonProblem(reason);
  const canSubmit = !problem && masterKey.trim().length > 0 && !submitting;

  async function submit() {
    setTouched(true);
    if (!target || !canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      const res = await governanceApi.startImpersonation(
        { tenantId: target.tenantId, userId: target.userId, reason: reason.trim() },
        masterKey.trim()
      );
      setMasterKey("");
      if (!isSafeViewUrl(res?.viewUrl)) {
        setError(
          "Core returned a view link this console will not open (it must be the app's /view-as page with the grant in the # fragment). The session was started; end it from Impersonation."
        );
        return;
      }
      // With noopener, window.open always returns null - no way to detect a
      // blocked pop-up, so the toast says what to do if nothing appeared.
      window.open(res.viewUrl, "_blank", "noopener,noreferrer");
      const who = res.userEmail || target.label;
      toast.success(`View-as started for ${who}; it ends in 30 minutes.`);
      setStarted({ viewUrl: res.viewUrl, who });
    } catch (err: any) {
      // toJson already toasted the server reason; keep it inline too.
      if (err?.status === 401 || err?.status === 403) setMasterKey("");
      setError(err?.message || "Could not start the view-as session.");
    } finally {
      setSubmitting(false);
    }
  }

  return createPortal(
    <AnimatePresence>
      {open && (
        <motion.div
          className="fixed inset-0 z-[100] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.15 }}
          onClick={submitting ? undefined : onClose}
        >
          <motion.div
            ref={dialogRef}
            className="w-full max-w-md rounded-xl border border-border bg-card p-5 shadow-lg outline-none"
            initial={{ opacity: 0, scale: 0.96, y: 12 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.96, y: 8 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="view-as-title"
            tabIndex={-1}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-2">
                <Eye size={16} className="text-primary" />
                <h2 id="view-as-title" className="text-sm font-semibold text-foreground">
                  {tHC("s10")}
                </h2>
              </div>
              <button
                type="button"
                onClick={onClose}
                disabled={submitting}
                aria-label="Close"
                className="rounded-md border-0 bg-transparent p-1 text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
              >
                <X size={16} />
              </button>
            </div>

            <p className="mt-2 text-sm text-foreground">
              {target?.label}
              {target?.tenantName ? <span className="text-muted-foreground"> · {target.tenantName}</span> : null}
            </p>

            <div
              className="mt-3 flex gap-2 rounded-lg border border-[var(--warning-border)] bg-[var(--warning-bg)] p-3 text-xs text-foreground"
              role="note"
            >
              <ShieldAlert size={14} className="mt-0.5 shrink-0 text-[var(--warning-text)]" />
              <span>{tHC("s9")}</span>
            </div>

            <div className="mt-3 space-y-1.5">
              <Label htmlFor="view-as-reason" className="text-xs text-muted-foreground">
                {tHC("s8")} {IMPERSONATION_REASON_MIN} characters)
              </Label>
              <Textarea
                id="view-as-reason"
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                onBlur={() => setTouched(true)}
                placeholder={tHC("s7")}
                disabled={submitting}
                maxLength={500}
                aria-invalid={touched && !!problem}
                aria-describedby="view-as-reason-hint"
              />
              <p
                id="view-as-reason-hint"
                className={`text-[11px] ${touched && problem ? "text-destructive" : "text-muted-foreground"}`}
              >
                {touched && problem ? problem : `${reason.trim().length} / ${IMPERSONATION_REASON_MIN}+ characters`}
              </p>
            </div>

            <div className="mt-3 space-y-1.5">
              <Label htmlFor="view-as-master-key" className="text-xs text-muted-foreground">
                {tHC("s6")}
              </Label>
              <Input
                id="view-as-master-key"
                type="password"
                value={masterKey}
                onChange={(e: any) => setMasterKey(e.target.value)}
                onKeyDown={(e: any) => e.key === "Enter" && void submit()}
                autoComplete="off"
                disabled={submitting}
              />
              <p className="text-[11px] text-muted-foreground">{tHC("s5")}</p>
            </div>

            {started && (
              <div className="mt-3 space-y-2 rounded-lg border border-border p-3 text-xs" role="status">
                <p>
                  {tHC("s4")} <span className="font-medium">{started.who}</span> {tHC("s3")}
                </p>
                <a
                  href={started.viewUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-primary underline underline-offset-2"
                >
                  <Eye size={12} /> {tHC("s2")}
                </a>
                <p className="text-muted-foreground">{tHC("s1")}</p>
              </div>
            )}

            {error && (
              <p className="mt-3 text-xs text-destructive" role="alert">
                {error}
              </p>
            )}

            <div className="mt-4 flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={onClose} disabled={submitting}>
                {started ? "Done" : "Cancel"}
              </Button>
              {!started && (
                <Button size="sm" disabled={!canSubmit} onClick={() => void submit()}>
                  <Eye /> {submitting ? "Starting..." : "Start and open"}
                </Button>
              )}
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}
