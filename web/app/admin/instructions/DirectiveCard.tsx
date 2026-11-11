"use client";

import { useEffect, useState } from "react";
import { ChevronDown, TriangleAlert } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { api } from "../../lib/api";
import { MAX_DIRECTIVE_CHARS } from "../../lib/api/promptDirectives";
import type { PromptDirective } from "../../lib/api/promptDirectives";
import { errorMessage } from "../../lib/errors";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Checkbox } from "../../components/ui/checkbox";
import { Label } from "../../components/ui/label";
import { Switch } from "../../components/ui/switch";
import { Textarea } from "../../components/ui/textarea";

type Status = "idle" | "saving" | "saved" | "error";

/** One built-in directive: toggle it, read it, rewrite it, or put the shipped wording back. */
export default function DirectiveCard({
  directive,
  canEdit,
  onChanged,
}: {
  directive: PromptDirective;
  canEdit: boolean;
  onChanged: () => Promise<void> | void;
}) {
  const t = useTranslations("promptDirectives");
  const [open, setOpen] = useState(false);
  const [enabled, setEnabled] = useState(directive.enabled);
  const [text, setText] = useState(directive.effectiveText ?? "");
  const [ack, setAck] = useState(false);
  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState<string | null>(null);

  // Resync when the server copy changes (after a save, reset or reload).
  useEffect(() => {
    setEnabled(directive.enabled);
    setText(directive.effectiveText ?? "");
    setAck(false);
  }, [directive.enabled, directive.effectiveText]);

  const dirty = enabled !== directive.enabled || text !== (directive.effectiveText ?? "");
  const needsAck = directive.securityCritical && dirty;
  const tooLong = text.length > MAX_DIRECTIVE_CHARS;
  const busy = status === "saving";
  const canSave = canEdit && dirty && !busy && !tooLong && (!needsAck || ack);
  const canRestore = canEdit && !busy && (directive.overridden || text !== directive.defaultText);
  const bodyId = `directive-${directive.key}`;

  const edit = (apply: () => void) => {
    apply();
    setStatus("idle");
    setError(null);
  };

  async function save() {
    setStatus("saving");
    setError(null);
    try {
      // Same as the shipped wording (or blank) means "no override": the server then follows future default changes.
      const sameAsDefault = text.trim() === "" || text === directive.defaultText;
      await api.savePromptDirective(directive.key, {
        enabled,
        text: sameAsDefault ? null : text,
        acknowledgeRisk: ack,
      });
      await onChanged();
      setStatus("saved");
    } catch (e: unknown) {
      setError(errorMessage(e, t("saveFailed")));
      setStatus("error");
    }
  }

  async function restore() {
    if (!directive.overridden) {
      edit(() => setText(directive.defaultText));
      return;
    }
    setStatus("saving");
    setError(null);
    try {
      await api.resetPromptDirective(directive.key);
      await onChanged();
      setStatus("saved");
    } catch (e: unknown) {
      setError(errorMessage(e, t("saveFailed")));
      setStatus("error");
    }
  }

  return (
    <Card data-testid={`directive-${directive.key}`}>
      <CardHeader className="gap-2">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle className="text-sm">{directive.title}</CardTitle>
              <Badge variant={directive.overridden ? "accent" : "secondary"}>
                {directive.overridden ? t("customised") : t("default")}
              </Badge>
              {directive.securityCritical && <Badge variant="outline">{t("securityCritical")}</Badge>}
            </div>
            <CardDescription className="mt-1 text-xs">{directive.description}</CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Label htmlFor={`${bodyId}-enabled`} className="text-xs text-muted-foreground">
              {enabled ? t("on") : t("off")}
            </Label>
            <Switch
              id={`${bodyId}-enabled`}
              checked={enabled}
              disabled={!canEdit || busy}
              aria-label={t("enableDirective", { title: directive.title })}
              onCheckedChange={(v) => edit(() => setEnabled(v))}
            />
          </div>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="w-fit"
          aria-expanded={open}
          aria-controls={bodyId}
          onClick={() => setOpen((o) => !o)}
        >
          <ChevronDown className={`size-4 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden="true" />
          {open ? t("hideText") : t("showText")}
        </Button>
      </CardHeader>

      {open && (
        <CardContent id={bodyId} className="flex flex-col gap-3">
          <Label htmlFor={`${bodyId}-text`} className="text-xs">
            {t("textLabel", { title: directive.title })}
          </Label>
          <Textarea
            id={`${bodyId}-text`}
            className="min-h-32 text-sm"
            value={text}
            readOnly={!canEdit}
            onChange={(e) => edit(() => setText(e.target.value))}
          />
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
            <span>
              {directive.placeholders.length > 0
                ? t("placeholders", { list: directive.placeholders.join(", ") })
                : t("noPlaceholders")}
            </span>
            <span className={tooLong ? "text-destructive" : undefined}>
              {t("charCount", { used: text.length, max: MAX_DIRECTIVE_CHARS })}
            </span>
          </div>

          {directive.securityCritical && (
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

          {status === "error" && error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" size="sm" disabled={!canSave} onClick={save}>
              {busy ? t("saving") : t("save")}
            </Button>
            <Button type="button" size="sm" variant="outline" disabled={!canRestore} onClick={restore}>
              {t("restoreDefault")}
            </Button>
            {status === "saved" && !dirty && (
              <span role="status" className="text-xs text-muted-foreground">
                {t("saved")}
              </span>
            )}
          </div>
        </CardContent>
      )}
    </Card>
  );
}
