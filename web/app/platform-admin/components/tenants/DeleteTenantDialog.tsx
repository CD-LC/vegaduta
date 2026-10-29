"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { X, AlertTriangle } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useDialogFocus } from "../../lib/useDialogFocus";
import { useTranslations } from "../../../lib/i18n";

// Platform-admin "delete tenant completely" control - the type-the-name-to-
// confirm step, modeled on TemplateReasonDialog's chrome (portal + escape/
// backdrop-close + disabled-until-valid submit) but swapping the reason
// Textarea for an Input that must match `tenantName` EXACTLY, same pattern
// GitHub/AWS resource deletion uses and the one this platform's own
// self-service tenant erasure (TenantDataDeletionRequest) already
// established server-side. Deliberately lists every system this reaches so
// the scope is never a surprise - see TenantDataErasureService/
// PlatformTenantDeletionOrchestrationService for what each line maps to.
export default function DeleteTenantDialog({
  open,
  tenantName,
  submitting = false,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  tenantName: string;
  submitting?: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
    const tHC = useTranslations("hcPlatformadminComponentsTenantsDeleteTenantDialog");
  const [typed, setTyped] = useState("");
  const [mounted, setMounted] = useState(false);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  useDialogFocus(open, dialogRef);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (open) setTyped("");
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

  const matches = typed === tenantName;
  const canConfirm = matches && !submitting;

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
            className="w-full max-w-md rounded-xl border border-destructive/30 bg-card p-5 shadow-lg outline-none"
            initial={{ opacity: 0, scale: 0.96, y: 12 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.96, y: 8 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="delete-tenant-dialog-title"
            tabIndex={-1}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-2">
                <AlertTriangle size={16} className="text-destructive" />
                <h2 id="delete-tenant-dialog-title" className="text-sm font-semibold text-foreground">
                  Delete {tenantName} completely
                </h2>
              </div>
              <button
                type="button"
                onClick={onCancel}
                disabled={submitting}
                aria-label="Close"
                className="rounded-md border-0 bg-transparent p-1 text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
              >
                <X size={16} />
              </button>
            </div>

            <p className="mt-2 text-xs text-muted-foreground">
              {tHC("hcPlatformadminComponentsTenantsDeleteTenantDialog.s6")}
            </p>
            <ul className="mt-1.5 list-inside list-disc space-y-0.5 text-xs text-muted-foreground">
              <li>{tHC("hcPlatformadminComponentsTenantsDeleteTenantDialog.s5")}</li>
              <li>{tHC("hcPlatformadminComponentsTenantsDeleteTenantDialog.s4")}</li>
              <li>{tHC("hcPlatformadminComponentsTenantsDeleteTenantDialog.s3")}</li>
              <li>{tHC("hcPlatformadminComponentsTenantsDeleteTenantDialog.s2")}</li>
            </ul>

            <div className="mt-3 space-y-1.5">
              <Label htmlFor="delete-tenant-confirm-input" className="text-xs text-muted-foreground">
                Type <span className="font-mono font-semibold text-foreground">{tenantName}</span> {tHC("hcPlatformadminComponentsTenantsDeleteTenantDialog.s1")}
              </Label>
              <Input
                id="delete-tenant-confirm-input"
                value={typed}
                onChange={(e: any) => setTyped(e.target.value)}
                placeholder={tenantName}
                autoComplete="off"
                disabled={submitting}
              />
            </div>

            <div className="mt-4 flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={onCancel} disabled={submitting}>
                Cancel
              </Button>
              <Button variant="destructive" size="sm" disabled={!canConfirm} onClick={onConfirm}>
                {submitting ? "Deleting…" : "Delete permanently"}
              </Button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}
