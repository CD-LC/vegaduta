"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { AnimatePresence, motion } from "motion/react";
import { X, KeyRound } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { useDialogFocus } from "../../lib/useDialogFocus";
import { useTranslations } from "../../../lib/i18n";

// Platform-admin "delete tenant completely" control - the master-key unlock
// step. Entered once per browser session (the caller holds the value in
// memory only - see tenants/page.tsx's masterKey state - never localStorage,
// never sent anywhere until an actual delete is confirmed). This is a UX
// convenience, not a weaker security boundary: TenantDeletionController
// re-validates the key server-side on EVERY delete call, constant-time,
// fail-closed if PLATFORM_MASTER_KEY is unset on the server.
export default function MasterKeyPrompt({
  open,
  submitting = false,
  description,
  onCancel,
  onSubmit,
}: {
  open: boolean;
  submitting?: boolean;
  /** Overrides the default "unlocks tenant deletion" copy (e.g. sandbox reclaim). */
  description?: string;
  onCancel: () => void;
  onSubmit: (key: string) => void;
}) {
    const tHC = useTranslations("hcPlatformadminComponentsTenantsMasterKeyPrompt");
  const [value, setValue] = useState("");
  const [mounted, setMounted] = useState(false);
  const dialogRef = useRef<HTMLDivElement | null>(null);
  useDialogFocus(open, dialogRef);

  useEffect(() => setMounted(true), []);

  useEffect(() => {
    if (open) setValue("");
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

  const canSubmit = value.trim().length > 0 && !submitting;

  function handleSubmit() {
    if (!canSubmit) return;
    onSubmit(value.trim());
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
            className="w-full max-w-sm rounded-xl border border-border bg-card p-5 shadow-lg outline-none"
            initial={{ opacity: 0, scale: 0.96, y: 12 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.96, y: 8 }}
            transition={{ duration: 0.2, ease: [0.16, 1, 0.3, 1] }}
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="master-key-prompt-title"
            tabIndex={-1}
          >
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-center gap-2">
                <KeyRound size={16} className="text-primary" />
                <h2 id="master-key-prompt-title" className="text-sm font-semibold text-foreground">
                  {tHC("s2")}
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
              {description ||
                "Unlocks tenant deletion for the rest of this browser session. It is re-checked on every deletion, not just this once."}
            </p>

            <div className="mt-3 space-y-1.5">
              <Label htmlFor="master-key-input" className="text-xs text-muted-foreground">
                {tHC("s1")}
              </Label>
              <Input
                id="master-key-input"
                type="password"
                value={value}
                onChange={(e: any) => setValue(e.target.value)}
                onKeyDown={(e: any) => e.key === "Enter" && handleSubmit()}
                autoComplete="off"
                disabled={submitting}
              />
            </div>

            <div className="mt-4 flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={onCancel} disabled={submitting}>
                Cancel
              </Button>
              <Button size="sm" disabled={!canSubmit} onClick={handleSubmit}>
                Unlock
              </Button>
            </div>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>,
    document.body
  );
}
