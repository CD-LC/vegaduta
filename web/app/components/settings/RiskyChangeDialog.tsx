"use client";

import { useId, useRef, useState } from "react";
import { ShieldAlert, TriangleAlert } from "lucide-react";
import type { SettingRisk } from "../../lib/api/settings";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import { Button } from "../ui/button";
import { Checkbox } from "../ui/checkbox";
import { Textarea } from "../ui/textarea";
import { MIN_REASON_LENGTH } from "./settingsModel";
import { useSettingsText } from "./useSettingsText";

/** One change awaiting confirmation. Text is already localized/formatted by the caller. */
export interface RiskyChange {
  key: string;
  label: string;
  risk: SettingRisk;
  fromText: string;
  toText: string;
  /** The setting's longer explanation (helpKey), shown as "what this changes". May be empty. */
  consequence: string;
  /** "reset": the override is removed and the value returns to the one it inherits (to). Default "change". */
  kind?: "change" | "reset";
  /** Security keys: which direction is stricter, already localized. Empty/absent for the rest. */
  strictness?: string;
}

/**
 * Confirmation for changes to settings whose risk is high or security: lists every change with its consequence,
 * requires a typed reason (recorded in the audit log) and, when a security setting is involved, an explicit
 * acknowledgment. Focus lands in the reason box; Escape or Cancel changes nothing.
 *
 * onConfirm may be async: the dialog stays open and busy until it settles, then closes.
 */
export default function RiskyChangeDialog({
  open,
  changes,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  changes: RiskyChange[];
  onConfirm: (reason: string) => void | Promise<void>;
  onCancel: () => void;
}) {
  const { ui } = useSettingsText();
  const [reason, setReason] = useState("");
  const [ack, setAck] = useState(false);
  const [busy, setBusy] = useState(false);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const reasonId = useId();
  const hintId = useId();
  const hasSecurity = changes.some((c) => c.risk === "security");
  const reasonOk = reason.trim().length >= MIN_REASON_LENGTH;
  const blocked = busy || !reasonOk || (hasSecurity && !ack);

  const close = () => {
    setReason("");
    setAck(false);
    onCancel();
  };
  const submit = async () => {
    setBusy(true);
    try {
      await onConfirm(reason.trim());
      setReason("");
      setAck(false);
    } finally {
      setBusy(false);
    }
  };

  return (
    <AlertDialog
      open={open}
      onOpenChange={(o) => {
        if (!o && !busy) close();
      }}
    >
      <AlertDialogContent
        data-testid="risky-change-dialog"
        className="max-h-[90vh] overflow-y-auto"
        onOpenAutoFocus={(e) => {
          // The reason box is the first thing to do; Radix would otherwise focus the first button.
          e.preventDefault();
          reasonRef.current?.focus();
        }}
      >
        <AlertDialogHeader>
          <AlertDialogTitle className="flex items-center gap-2">
            {hasSecurity ? (
              <ShieldAlert aria-hidden="true" className="size-5 text-destructive" />
            ) : (
              <TriangleAlert aria-hidden="true" className="size-5 text-[var(--state-warning)]" />
            )}
            {ui("risky.title", { count: changes.length })}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {ui(hasSecurity ? "risky.descriptionSecurity" : "risky.description")}
          </AlertDialogDescription>
        </AlertDialogHeader>

        <ul className="space-y-3" aria-label={ui("risky.listLabel")}>
          {changes.map((c) => (
            <li
              key={c.key}
              className="rounded-md border border-border bg-secondary/30 p-3 text-sm"
              data-testid={`risky-change-${c.key}`}
            >
              <p className="font-medium">
                {c.label}
                <span className="ms-2 text-xs font-normal text-muted-foreground">{ui(`risk.${c.risk}`)}</span>
              </p>
              <p className="mt-1 text-xs">
                <span className="text-muted-foreground">{ui("risky.from")}</span> <del>{c.fromText}</del>{" "}
                <span className="text-muted-foreground">{ui("risky.to")}</span>{" "}
                <ins className="font-medium no-underline">{c.toText}</ins>
              </p>
              {c.kind === "reset" ? (
                <p className="mt-1 text-xs text-muted-foreground" data-testid="risky-reset-note">
                  {ui("risky.resetNote")}
                </p>
              ) : null}
              {c.risk === "security" ? (
                <p className="mt-1 text-xs text-muted-foreground">{ui("risky.securityNote")}</p>
              ) : null}
              {c.strictness ? (
                <p className="mt-1 text-xs text-muted-foreground" data-testid="risky-strictness">
                  {c.strictness}
                </p>
              ) : null}
              {c.consequence ? (
                <p className="mt-1 text-xs text-muted-foreground">
                  <span className="font-medium text-foreground">{ui("risky.consequence")}</span> {c.consequence}
                </p>
              ) : null}
            </li>
          ))}
        </ul>

        <div className="space-y-1">
          <label htmlFor={reasonId} className="text-sm font-medium">
            {ui("risky.reasonLabel")}
          </label>
          <Textarea
            id={reasonId}
            ref={reasonRef}
            value={reason}
            required
            aria-required="true"
            aria-describedby={hintId}
            placeholder={ui("risky.reasonPlaceholder")}
            onChange={(e) => setReason(e.target.value)}
          />
          <p id={hintId} className="text-xs text-muted-foreground">
            {ui("risky.reasonHint", { min: MIN_REASON_LENGTH })}
          </p>
        </div>

        {hasSecurity ? (
          <div className="flex items-start gap-2 text-sm">
            <Checkbox
              id={`${reasonId}-ack`}
              checked={ack}
              onCheckedChange={setAck}
              aria-labelledby={`${reasonId}-ack-label`}
            />
            <label id={`${reasonId}-ack-label`} htmlFor={`${reasonId}-ack`}>
              {ui("risky.acknowledge")}
            </label>
          </div>
        ) : null}

        <AlertDialogFooter>
          <Button variant="outline" disabled={busy} onClick={close}>
            {ui("risky.cancel")}
          </Button>
          <Button variant="destructive" disabled={blocked} onClick={() => void submit()}>
            {ui("risky.confirm")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
