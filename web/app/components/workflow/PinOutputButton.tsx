"use client";

// "Pin output" / "Pin this output" for a payload the UI is already showing (a
// step-test result, a historical node execution, a trigger's last event).
// Renders nothing without edit access, so hosts can drop it in unconditionally.
// A trace payload is a redacted, size-capped copy: when it is flagged truncated
// or carries the "[redacted]" marker the button asks first, because a pin of it
// will not behave like the real data.

import { useState } from "react";
import { Check, Pin } from "lucide-react";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { PIN_MAX_CHARS, type PinSource } from "../../lib/api/workflowPins";
import { compactPinText, payloadHasRedaction, payloadNeedsPinConfirm } from "./pinModel";
import { usePinsContext, type PinsContextValue } from "./PinsContext";
import "./pins.css";

type Translator = (key: string, values?: Record<string, string | number>) => string;

export interface PinOutputButtonProps {
  nodeId: string;
  /** The output text to pin. Empty/absent hides the button. */
  data: string | null | undefined;
  source: PinSource;
  sourceRunId?: string | null;
  /** The payload is a server-truncated capture (trace entry `outputTruncated`). */
  truncated?: boolean | null;
  /** Wording: "output" (default) or "this" for historical executions. */
  label?: "output" | "this";
  className?: string;
}

/** Renders nothing (and needs no i18n provider) unless a canvas pin context with edit access is present. */
export default function PinOutputButton(props: PinOutputButtonProps) {
  const ctx = usePinsContext();
  if (!ctx || !ctx.canEdit || !props.nodeId || !props.data || props.data.trim() === "") return null;
  return <PinOutputButtonInner {...props} ctx={ctx} />;
}

function PinOutputButtonInner({
  ctx,
  nodeId,
  data,
  source,
  sourceRunId,
  truncated,
  label = "output",
  className,
}: PinOutputButtonProps & { ctx: PinsContextValue }) {
  const t = useTranslations("workflowCanvas") as Translator;
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);


  const existing = ctx.pins[nodeId];
  const alreadyPinned = !!existing && existing.data != null && compactPinText(existing.data) === compactPinText(data);

  async function doPin() {
    if (!data) return;
    setBusy(true);
    setConfirming(false);
    const text = compactPinText(data);
    if (text.length > PIN_MAX_CHARS) {
      toast.error(t("pinErrTooLarge", { max: new Intl.NumberFormat("en-US").format(PIN_MAX_CHARS) }));
    } else {
      await ctx.pinOutput({ nodeId, data: text, source, sourceRunId });
    }
    setBusy(false);
  }

  function click() {
    if (busy || alreadyPinned) return;
    if (payloadNeedsPinConfirm(data, truncated)) setConfirming(true);
    else void doPin();
  }

  const cut = !!truncated;
  const masked = payloadHasRedaction(data);
  const idleLabel = label === "this" ? t("pinThisOutput") : t("pinOutput");

  return (
    <span className={`wfx-pin-out ${className ?? ""}`.trim()}>
      <button
        type="button"
        className="wfx-pin-link"
        onClick={click}
        disabled={busy || alreadyPinned}
        aria-haspopup={confirming ? "dialog" : undefined}
        data-testid="pin-output-button"
      >
        {alreadyPinned ? <Check size={12} aria-hidden="true" /> : <Pin size={12} aria-hidden="true" />}
        {alreadyPinned ? t("pinPinned") : busy ? t("pinSaving") : idleLabel}
      </button>
      {confirming ? (
        <div className="wfx-pin-confirm" role="alertdialog" aria-label={t("pinConfirmTitle")} data-testid="pin-confirm">
          <p className="wfx-pin-confirm-title">{t("pinConfirmTitle")}</p>
          <p className="wfx-pin-confirm-text">
            {cut && masked ? t("pinConfirmBoth") : cut ? t("pinConfirmTruncated") : t("pinConfirmRedacted")}
          </p>
          <div className="wfx-pin-confirm-actions">
            <button type="button" className="wfx-pin-link" onClick={() => setConfirming(false)}>
              {t("cancel")}
            </button>
            <button type="button" className="wfx-pin-link wfx-pin-link-strong" onClick={() => void doPin()} autoFocus>
              {t("pinConfirmAction")}
            </button>
          </div>
        </div>
      ) : null}
    </span>
  );
}
