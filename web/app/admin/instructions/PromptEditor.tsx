"use client";

import { useEffect, useRef, useState } from "react";
import { History, TriangleAlert } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { api } from "../../lib/api";
import type { PromptValidation, PromptView } from "../../lib/api/promptRegistry";
import { errorMessage, errorStatus } from "../../lib/errors";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Checkbox } from "../../components/ui/checkbox";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Switch } from "../../components/ui/switch";
import { Textarea } from "../../components/ui/textarea";
import PromptHistory from "./PromptHistory";
import { overrideAt, startingEnabled, startingText, wordingBelow, type EditTarget } from "./promptRegistryModel";

type Status = "idle" | "saving" | "saved" | "error";

/** How long typing pauses before the draft is checked against the key's placeholders and limits. */
const VALIDATE_DELAY_MS = 350;

/**
 * Edits one key at one scope. The draft is checked by the server as you type (unknown or missing {placeholders},
 * length, a prompt that cannot be switched off) and the problems are listed under the text; saving is blocked
 * while there are any. A save carries the version the editor saw, so a concurrent change is a conflict, not a
 * silent overwrite. Reverting removes the override at this scope; the history below lists every version.
 */
export default function PromptEditor({
  view,
  target,
  canEdit,
  onChanged,
}: {
  view: PromptView;
  target: EditTarget;
  canEdit: boolean;
  onChanged: () => Promise<void> | void;
}) {
  const t = useTranslations("promptDirectives");
  const def = view.definition;
  const current = overrideAt(view, target);
  const baseText = startingText(view, target);
  const baseEnabled = startingEnabled(view, target);
  const below = wordingBelow(view, target);
  const version = current?.version ?? 0;

  const [text, setText] = useState(baseText);
  const [enabled, setEnabled] = useState(baseEnabled);
  const [ack, setAck] = useState(false);
  const [reason, setReason] = useState("");
  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState<string | null>(null);
  const [validation, setValidation] = useState<PromptValidation | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyToken, setHistoryToken] = useState(0);
  const draft = useRef(0);

  // Resync when the stored copy moves (after a save, revert, restore or reload) or the scope changes.
  useEffect(() => {
    setText(baseText);
    setEnabled(baseEnabled);
    setAck(false);
    setValidation(null);
  }, [baseText, baseEnabled, version, target.scope, target.agentId]);

  const dirty = text !== baseText || enabled !== baseEnabled;
  const tooLong = text.length > def.maxLength;
  const busy = status === "saving";

  // Server-side check of the draft, debounced; a stale answer (typed on since) is dropped.
  useEffect(() => {
    if (!dirty || !canEdit) {
      setValidation(null);
      return undefined;
    }
    const ticket = ++draft.current;
    const timer = setTimeout(() => {
      api
        .validatePrompt(def.key, { text: text.trim() === "" ? null : text, enabled })
        .then((v) => {
          if (ticket === draft.current) setValidation(v);
        })
        .catch(() => {
          // The save repeats every check and reports its own error; a failed live check says nothing.
          if (ticket === draft.current) setValidation(null);
        });
    }, VALIDATE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [text, enabled, dirty, canEdit, def.key]);

  const problems = validation?.problems ?? [];
  const needsAck = dirty && (validation?.requiresAcknowledgement ?? def.securityCritical);
  const canSave = canEdit && dirty && !busy && !tooLong && problems.length === 0 && (!needsAck || ack);
  const bodyId = `prompt-${def.key}`;
  const targetArg = { scope: target.scope, agentId: target.agentId };

  const edit = (apply: () => void) => {
    apply();
    setStatus("idle");
    setError(null);
  };

  async function run(action: () => Promise<unknown>, failure: string) {
    setStatus("saving");
    setError(null);
    try {
      await action();
      await onChanged();
      setHistoryToken((n) => n + 1);
      setReason("");
      setStatus("saved");
    } catch (e: unknown) {
      const conflict = errorStatus(e) === 409;
      setError(conflict ? t("regConflict") : errorMessage(e, failure));
      setStatus("error");
    }
  }

  const save = () =>
    run(
      () =>
        api.savePromptOverride(def.key, targetArg, {
          text: text.trim() === "" ? null : text,
          enabled,
          acknowledgeRisk: ack,
          reason: reason.trim() || null,
          expectedVersion: version,
        }),
      t("saveFailed")
    );

  const revert = () =>
    run(
      () => api.revertPromptOverride(def.key, targetArg, { reason: reason.trim() || null, expectedVersion: version }),
      t("saveFailed")
    );

  return (
    <div id={bodyId} className="flex flex-col gap-3" data-testid={`editor-${def.key}`}>
      <p className="text-xs text-muted-foreground">
        {current
          ? t("regEditingOverride", { scope: t(`regScope_${target.scope}`), version: current.version })
          : t("regEditingNone", { scope: t(`regScope_${target.scope}`), below: t(`regSource_${below.source}`) })}
      </p>

      {def.disableable && (
        <div className="flex items-center gap-2">
          <Switch
            id={`${bodyId}-enabled`}
            checked={enabled}
            disabled={!canEdit || busy}
            aria-label={t("enableDirective", { title: def.title })}
            onCheckedChange={(v) => edit(() => setEnabled(v))}
          />
          <Label htmlFor={`${bodyId}-enabled`} className="text-xs text-muted-foreground">
            {enabled ? t("on") : t("off")}
          </Label>
        </div>
      )}

      <Label htmlFor={`${bodyId}-text`} className="text-xs">
        {t("textLabel", { title: def.title })}
      </Label>
      <Textarea
        id={`${bodyId}-text`}
        className="min-h-32 font-mono text-xs"
        value={text}
        readOnly={!canEdit}
        aria-invalid={problems.length > 0 || tooLong}
        aria-describedby={problems.length > 0 ? `${bodyId}-problems` : undefined}
        onChange={(e) => edit(() => setText(e.target.value))}
      />

      <div className="flex flex-wrap items-start justify-between gap-2 text-xs text-muted-foreground">
        <span>
          {def.placeholders.length > 0 ? (
            <>
              {t("regPlaceholders")}{" "}
              {def.placeholders.map((p, i) => (
                <span key={p.name} title={p.description ?? undefined}>
                  <code className="font-mono">{`{${p.name}}`}</code>
                  {p.required ? ` ${t("regRequired")}` : ""}
                  {i < def.placeholders.length - 1 ? ", " : ""}
                </span>
              ))}
            </>
          ) : (
            t("noPlaceholders")
          )}
        </span>
        <span className={tooLong ? "text-destructive" : undefined}>
          {t("charCount", { used: text.length, max: def.maxLength })}
        </span>
      </div>

      {problems.length > 0 && (
        <Alert variant="destructive" id={`${bodyId}-problems`} role="alert">
          <TriangleAlert aria-hidden="true" />
          <AlertTitle>{t("regProblemsTitle")}</AlertTitle>
          <AlertDescription>
            <ul className="list-disc ps-4">
              {problems.map((p) => (
                <li key={p}>{p}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}

      {def.securityCritical && (
        <Alert variant="warning">
          <TriangleAlert aria-hidden="true" />
          <AlertTitle>{t("riskTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("riskBody")}</p>
            <div className="mt-2 flex items-start gap-2">
              <Checkbox
                id={`${bodyId}-ack`}
                checked={ack}
                disabled={!canEdit || busy}
                onCheckedChange={(v) => setAck(v === true)}
              />
              <Label htmlFor={`${bodyId}-ack`} className="text-xs leading-snug">
                {t("riskConfirm")}
              </Label>
            </div>
          </AlertDescription>
        </Alert>
      )}

      {canEdit && (
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${bodyId}-reason`} className="text-xs">
            {t("regReasonLabel")}
          </Label>
          <Input
            id={`${bodyId}-reason`}
            value={reason}
            maxLength={500}
            placeholder={t("regReasonPlaceholder")}
            onChange={(e) => setReason(e.target.value)}
          />
        </div>
      )}

      {status === "error" && error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" size="sm" disabled={!canSave} onClick={() => void save()}>
          {busy ? t("saving") : t("save")}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          disabled={!canEdit || busy || !current}
          onClick={() => void revert()}
        >
          {t("regRevert", { to: t(`regSource_${below.source}`) })}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="ghost"
          aria-expanded={historyOpen}
          aria-controls={`${bodyId}-history`}
          onClick={() => setHistoryOpen((o) => !o)}
        >
          <History className="size-4" aria-hidden="true" />
          {historyOpen ? t("regHideHistory") : t("regShowHistory")}
        </Button>
        {status === "saved" && !dirty && (
          <span role="status" className="text-xs text-muted-foreground">
            {t("saved")}
          </span>
        )}
      </div>

      {historyOpen && (
        <div id={`${bodyId}-history`}>
          <PromptHistory
            promptKey={def.key}
            title={def.title}
            securityCritical={def.securityCritical}
            target={targetArg}
            canEdit={canEdit}
            reloadToken={historyToken}
            onRestored={async () => {
              await onChanged();
              setHistoryToken((n) => n + 1);
            }}
          />
        </div>
      )}
    </div>
  );
}
