"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { X, AlertTriangle } from "lucide-react";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import { Label } from "../ui/label";
import { useDialogFocus } from "../../lib/useDialogFocus";
import { useTranslations } from "../../../lib/i18n";

// Reason confirmation modal, shared by Reject (reason REQUIRED - server-
// enforced, confirmed against the real backend 2026-07-30) and Withdraw
// (reason accepted but OPTIONAL server-side, same source confirmation) -
// docs/COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md's REJECTED/WITHDRAWN
// states. When `required` (default true), the confirm button stays disabled
// until the trimmed reason is non-empty, so an empty-reason reject is
// impossible from this UI; pass `required={false}` for withdraw so a blank
// reason is allowed through (still recorded/shown to the submitter if
// given). Portal + escape-to-close + backdrop-click-to-close, same chrome
// pattern as BrandIdentityModal.
export default function TemplateReasonDialog({
  open,
  title,
  description,
  confirmLabel = "Confirm",
  submitting = false,
  required = true,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  title: string;
  description?: string;
  confirmLabel?: string;
  submitting?: boolean;
  required?: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => void;
}) {
  const tHC = useTranslations("hcPlatformadminComponentsTemplatesTemplateReasonDialog");
  const [reason, setReason] = useState("");
  const [mounted, setMounted] = useState(false);
  const [touched, setTouched] = useState(false);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  useDialogFocus(open, dialogRef);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (open) {
      setReason("");
      setTouched(false);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onCancel]);

  if (!mounted) return null;

  const trimmed = reason.trim();
  const showError = required && touched && trimmed.length === 0;
  const canConfirm = (!required || trimmed.length > 0) && !submitting;

  function handleConfirm() {
    setTouched(true);
    if (!canConfirm) return;
    onConfirm(trimmed);
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
          onClick={submitting ? undefined : onCancel}
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
            aria-labelledby="template-reason-dialog-title"
            tabIndex={-1}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-2">
                <AlertTriangle size={16} className="text-[var(--warning)]" />
                <h2 id="template-reason-dialog-title" className="text-sm font-semibold text-foreground">
                  {title}
                </h2>
              </div>
              <button
                type="button"
                onClick={onCancel}
                aria-label="Close"
                className="rounded-md border-0 bg-transparent p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X size={16} />
              </button>
            </div>

            {description && <p className="mt-2 text-xs text-muted-foreground">{description}</p>}

            <div className="mt-3 space-y-1.5">
              <Label htmlFor="template-reason-input" className="text-xs text-muted-foreground">
                {tHC("s3")}
                {required ? " *" : " - optional"}
              </Label>
              <Textarea
                id="template-reason-input"
                value={reason}
                onChange={(e: any) => setReason(e.target.value)}
                onBlur={() => setTouched(true)}
                rows={4}
                placeholder={tHC("s2")}
                aria-invalid={showError}
              />
              {showError && <p className="text-xs text-destructive">{tHC("s1")}</p>}
            </div>

            <div className="mt-4 flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={onCancel} disabled={submitting}>
                Cancel
              </Button>
              <Button variant="destructive" size="sm" disabled={!canConfirm} onClick={handleConfirm}>
                {submitting ? "Submitting…" : confirmLabel}
              </Button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}
