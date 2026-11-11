"use client";

import { useRef } from "react";
import { X as CloseIcon, ShieldAlert } from "lucide-react";
import { Button } from "../ui/button";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";

export default function WorkflowValidationDialog({ setValidationErrors, t, validationErrors }: any) {
  // Focus in on open, Tab trap, Escape closes, focus back to the opener on close (FE-041).
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, () => setValidationErrors(null), {
    enabled: !!(validationErrors && validationErrors.length > 0),
  });

  return (
    <>
      {/* Validation dialog (#343) - "display a validation dialog/panel,
       * show all missing workflow information." Shared by both the
       * dedicated Validate Workflow button and a failed Activate (the one
       * save path that still enforces validation server-side). */}
      {validationErrors && validationErrors.length > 0 && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
          role="presentation"
          onClick={(e) => {
            if (e.target === e.currentTarget) setValidationErrors(null);
          }}
        >
          <div
            className="wf-validate-modal w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-xl"
            ref={dialogRef}
            tabIndex={-1}
            role="dialog"
            aria-modal="true"
          >
            <div className="flex items-start justify-between gap-3">
              <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("validationModalTitle")}</h2>
              <button
                type="button"
                className="-me-1 -mt-1 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
                onClick={() => setValidationErrors(null)}
                aria-label={t("close")}
              >
                <CloseIcon size={16} />
              </button>
            </div>
            <p className="mt-1.5 text-sm text-muted-foreground">{t("validationModalHint")}</p>
            <ul className="wf-validate-list mt-4 space-y-2">
              {validationErrors.map((e: any, i: any) => (
                <li
                  key={i}
                  className="flex gap-2 rounded-lg border border-amber-500/25 bg-amber-500/10 px-3 py-2 text-sm text-amber-600 dark:text-amber-400"
                >
                  <ShieldAlert size={15} className="mt-0.5 shrink-0" />
                  <span className="text-foreground/90">{e}</span>
                </li>
              ))}
            </ul>
            <div className="mt-5 flex justify-end">
              <Button type="button" size="sm" onClick={() => setValidationErrors(null)}>
                {t("gotIt")}
              </Button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
