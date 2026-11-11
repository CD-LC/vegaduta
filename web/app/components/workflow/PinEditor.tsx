"use client";

// Manual pin editor: paste or edit the JSON a node should return in draft runs.
// Validation runs before anything is sent (empty, malformed JSON, over the
// 256,000-character cap) and the server's own 400s are shown inline via the
// same messages the toasts use.

import { useEffect, useId, useMemo, useRef, useState } from "react";
import { X as CloseIcon } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { PIN_MAX_CHARS, type PinResult, type PinSource, type WorkflowPin } from "../../lib/api/workflowPins";
import { Button } from "../ui/button";
import { Label } from "../ui/label";
import { TEXTAREA_CLASS } from "./workflowCanvasHelpers";
import {
  checkPinText,
  compactPinText,
  formatPinSize,
  formatPinTime,
  pinFailureText,
  pinSourceKey,
  prettyPinText,
  type PinRequest,
} from "./pinModel";
import "./pins.css";

type Translator = (key: string, values?: Record<string, string | number>) => string;

const NUM = new Intl.NumberFormat("en-US");

export interface PinEditorProps {
  nodeId: string;
  nodeLabel: string;
  /** The node's current pin, when it has one. `data` null = the viewer may not read it. */
  pin: WorkflowPin | null;
  /** Starting text for a node with no pin yet. */
  initialText?: string;
  initialSource?: PinSource;
  sourceRunId?: string;
  canEdit: boolean;
  onSave: (req: PinRequest) => Promise<PinResult<WorkflowPin>>;
  onClose: () => void;
}

export default function PinEditor({
  nodeId,
  nodeLabel,
  pin,
  initialText,
  initialSource,
  sourceRunId,
  canEdit,
  onSave,
  onClose,
}: PinEditorProps) {
  const t = useTranslations("workflowCanvas") as Translator;
  const uid = useId();
  const original = pin?.data ?? initialText ?? "";
  const hidden = !!pin && pin.data == null; // metadata-only viewer
  const [text, setText] = useState(() => prettyPinText(original));
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const areaRef = useRef<HTMLTextAreaElement | null>(null);
  const readOnly = !canEdit || hidden;

  useEffect(() => {
    areaRef.current?.focus();
  }, []);

  const check = useMemo(() => checkPinText(text), [text]);
  // Size is judged on what will be stored (compact), not on the pretty-printed view.
  const storedSize = useMemo(() => compactPinText(text).length, [text]);
  const overCap = storedSize > PIN_MAX_CHARS;

  let problem: string | null = null;
  if (!readOnly && text.trim() !== "") {
    if (overCap) problem = t("pinEditorErrTooLarge", { max: NUM.format(PIN_MAX_CHARS), size: NUM.format(storedSize) });
    else if (!check.ok && check.reason === "invalid-json")
      problem = t("pinEditorErrInvalidJson", { detail: check.detail ?? "" });
  }
  const plainText = check.ok && !check.json && text.trim() !== "";
  const canSave = !readOnly && !saving && text.trim() !== "" && !overCap && check.ok;

  async function save() {
    if (!canSave) return;
    const data = compactPinText(text);
    const unchanged = pin?.data != null && compactPinText(pin.data) === data;
    const fromInitial = !pin && initialText != null && compactPinText(initialText) === data;
    const source: PinSource = unchanged && pin ? pin.source : fromInitial && initialSource ? initialSource : "manual";
    const runId = unchanged && pin ? (pin.sourceRunId ?? undefined) : fromInitial ? sourceRunId : undefined;
    setSaving(true);
    setServerError(null);
    const r = await onSave({ nodeId, data, source, sourceRunId: runId });
    setSaving(false);
    if (r.ok) onClose();
    else setServerError(pinFailureText(t, r.kind, r.message));
  }

  const title = readOnly
    ? t("pinEditorTitleView", { node: nodeLabel })
    : pin
      ? t("pinEditorTitleEdit", { node: nodeLabel })
      : t("pinEditorTitleNew", { node: nodeLabel });

  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions, jsx-a11y/no-noninteractive-element-interactions -- backdrop click dismisses; Escape closes via the panel handler
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      onClick={() => !saving && onClose()}
    >
      {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions, jsx-a11y/no-noninteractive-element-interactions -- inner panel only stops click propagation; Escape is handled on it */}
      <div
        className="wfx-pin-editor max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
        onKeyDown={(e) => {
          if (e.key === "Escape" && !saving) {
            e.stopPropagation();
            onClose();
          } else if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
            e.preventDefault();
            void save();
          }
        }}
        role="dialog"
        aria-modal="true"
        aria-labelledby={`${uid}-title`}
      >
        <div className="flex items-start justify-between gap-3">
          <h2 id={`${uid}-title`} className="text-lg font-semibold tracking-tight text-foreground">
            {title}
          </h2>
          <button
            type="button"
            className="-me-1 -mt-1 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
            onClick={onClose}
            aria-label={t("close")}
          >
            <CloseIcon size={16} />
          </button>
        </div>
        <p className="mt-1.5 text-sm text-muted-foreground">{readOnly ? t("pinEditorHintView") : t("pinEditorHint")}</p>

        {pin ? (
          <p className="mt-2 text-xs text-muted-foreground">
            {t("pinBannerMeta", {
              source: t(pinSourceKey(pin.source)),
              when: formatPinTime(pin.pinnedAt),
              size: formatPinSize(pin.sizeChars),
            })}
          </p>
        ) : null}

        <div className="mt-4 space-y-1.5">
          <Label htmlFor={`${uid}-data`}>{t("pinEditorLabel")}</Label>
          {hidden ? (
            <p className="rounded-md border border-border bg-secondary p-3 text-sm text-muted-foreground" role="note">
              {t("pinBannerMetadataOnly")}
            </p>
          ) : (
            <textarea
              id={`${uid}-data`}
              ref={areaRef}
              rows={14}
              spellCheck={false}
              readOnly={readOnly}
              className={`${TEXTAREA_CLASS} font-mono text-xs`}
              value={text}
              onChange={(e) => {
                setText(e.target.value);
                setServerError(null);
              }}
              aria-invalid={problem ? true : undefined}
              aria-describedby={`${uid}-status`}
              placeholder={t("pinEditorPlaceholder")}
            />
          )}
          {!hidden ? (
            <div id={`${uid}-status`} className="flex flex-wrap items-center justify-between gap-2 text-xs">
              <span className={overCap ? "text-destructive" : "text-muted-foreground"} data-testid="pin-size-counter">
                {t("pinEditorSize", { size: NUM.format(storedSize), max: NUM.format(PIN_MAX_CHARS) })}
              </span>
              {!readOnly ? (
                <button
                  type="button"
                  className="wfx-pin-link"
                  onClick={() => setText(prettyPinText(text))}
                  disabled={!check.ok}
                >
                  {t("pinEditorPretty")}
                </button>
              ) : null}
            </div>
          ) : null}
          <div role="status" aria-live="polite">
            {problem ? (
              <p className="text-xs text-destructive" data-testid="pin-problem">
                {problem}
              </p>
            ) : null}
            {!problem && plainText ? <p className="text-xs text-muted-foreground">{t("pinEditorPlainText")}</p> : null}
            {serverError ? (
              <p className="text-xs text-destructive" data-testid="pin-server-error">
                {serverError}
              </p>
            ) : null}
          </div>
        </div>

        <div className="mt-6 flex items-center justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onClose} disabled={saving}>
            {readOnly ? t("close") : t("cancel")}
          </Button>
          {!readOnly ? (
            <Button type="button" size="sm" onClick={() => void save()} disabled={!canSave}>
              {saving ? t("pinEditorSaving") : t("pinEditorSave")}
            </Button>
          ) : null}
        </div>
      </div>
    </div>
  );
}
