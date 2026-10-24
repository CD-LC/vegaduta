"use client";

// ConfirmDialog: the replacement for window.confirm()/prompt(), built on
// ui/alert-dialog (modal, focus-trapped, Escape cancels).
//
// Usage:
//   <ConfirmDialog open={open} onOpenChange={setOpen} title="Delete agent?"
//     description="This cannot be undone." confirmLabel="Delete" destructive
//     requireReason onConfirm={(reason) => remove(id, reason)} />
// With requireReason the confirm button stays disabled until a non-blank
// reason is typed; the trimmed reason is passed to onConfirm. onConfirm may be
// async: the dialog stays open and busy until it settles, closes on success.

import { useState, type ReactNode } from "react";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "./alert-dialog";
import { Button } from "./button";
import { Textarea } from "./textarea";

export interface ConfirmDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  requireReason?: boolean;
  reasonLabel?: string;
  onConfirm: (reason?: string) => void | Promise<void>;
}

function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  destructive,
  requireReason,
  reasonLabel = "Reason",
  onConfirm,
}: ConfirmDialogProps) {
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const blocked = busy || (requireReason && reason.trim() === "");

  const submit = async () => {
    setBusy(true);
    try {
      await onConfirm(requireReason ? reason.trim() : undefined);
      setReason("");
      onOpenChange(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={(o) => { if (!busy) { if (!o) setReason(""); onOpenChange(o); } }}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          {description ? <AlertDialogDescription>{description}</AlertDialogDescription> : null}
        </AlertDialogHeader>
        {requireReason ? (
          <Textarea aria-label={reasonLabel} placeholder={reasonLabel} value={reason} onChange={(e) => setReason(e.target.value)} />
        ) : null}
        <AlertDialogFooter>
          {/* AlertDialogCancel (asChild) is what tells Radix where to put focus when the dialog opens; a bare
              Button leaves focus on the control underneath the overlay, out of keyboard reach. */}
          <AlertDialogCancel asChild>
            <Button variant="outline" disabled={busy} onClick={() => { setReason(""); onOpenChange(false); }}>
              {cancelLabel}
            </Button>
          </AlertDialogCancel>
          <Button variant={destructive ? "destructive" : "default"} disabled={blocked} onClick={() => void submit()}>
            {confirmLabel}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export { ConfirmDialog };
