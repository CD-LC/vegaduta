"use client";

// Themed rotate/revoke confirmation dialog for the MCP server access console.

import { useRef } from "react";
import { X, TriangleAlert } from "lucide-react";
import { Button } from "../../components/ui/button";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import { useEscapeToClose } from "./mcpGrants";

// Replaces window.confirm() for rotate/revoke - a native browser confirm()
// reads as a jarring OS-chrome popup dropped into an otherwise fully
// themed console, and can't carry the same warning affordance (icon,
// accent-matched danger styling) the rest of this app's destructive flows
// use. Same .wf-modal-backdrop/.wf-modal shell as KeyRevealDialog above,
// `danger` just swaps the confirm button to the same ax-wf-btn-warn treatment
// AdminUsersPanel/ApprovalsPanel already use for a destructive/consequential
// row action (see toggleEnabled's disable button, handleReject's button).
export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  cancelLabel,
  danger,
  busy,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel: string;
  danger: boolean;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const closeBtnRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  useEscapeToClose(onCancel, true);
  // Initial focus on the close button, Tab trap, focus back to the opener on close (FE-041). Escape stays with
  // useEscapeToClose above.
  useDialogFocus(dialogRef, closeBtnRef);

  return (
    <div
      className="wf-modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget && !busy) onCancel();
      }}
    >
      <div ref={dialogRef} className="wf-modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="wf-modal-header">
          <h2 style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <TriangleAlert size={16} style={{ color: danger ? "var(--danger-text)" : undefined }} aria-hidden="true" />
            {title}
          </h2>
          <Button
            ref={closeBtnRef}
            variant="ghost"
            size="icon"
            className="wf-modal-close"
            onClick={onCancel}
            aria-label={cancelLabel}
            disabled={busy}
          >
            <X size={16} aria-hidden="true" />
          </Button>
        </div>

        <p className="hint" style={{ marginBottom: 0 }}>
          {message}
        </p>

        <div className="row" style={{ justifyContent: "flex-end", marginTop: "1.1rem" }}>
          <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onCancel}>
            {cancelLabel}
          </Button>
          <Button
            type="button"
            variant={danger ? "destructive" : "default"}
            size="sm"
            disabled={busy}
            onClick={onConfirm}
          >
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  );
}
