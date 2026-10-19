"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { api } from "../../lib/api";
import { MAX_CUSTOM_INSTRUCTIONS_CHARS } from "../../lib/api/promptDirectives";
import type { CustomInstructions } from "../../lib/api/promptDirectives";
import { errorMessage } from "../../lib/errors";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Label } from "../../components/ui/label";
import { Switch } from "../../components/ui/switch";
import { Textarea } from "../../components/ui/textarea";

type Status = "idle" | "saving" | "saved" | "error";

/** Free text appended for every agent in the workspace. */
export default function CustomInstructionsCard({
  value,
  canEdit,
  onChanged,
}: {
  value: CustomInstructions;
  canEdit: boolean;
  onChanged: () => Promise<void> | void;
}) {
  const t = useTranslations("promptDirectives");
  const serverText = value.text ?? "";
  const [enabled, setEnabled] = useState(value.enabled);
  const [text, setText] = useState(serverText);
  const [status, setStatus] = useState<Status>("idle");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setEnabled(value.enabled);
    setText(value.text ?? "");
  }, [value.enabled, value.text]);

  const dirty = enabled !== value.enabled || text !== serverText;
  const tooLong = text.length > MAX_CUSTOM_INSTRUCTIONS_CHARS;
  const busy = status === "saving";

  const edit = (apply: () => void) => {
    apply();
    setStatus("idle");
    setError(null);
  };

  async function save() {
    setStatus("saving");
    setError(null);
    try {
      await api.saveCustomInstructions({ enabled, text: text.trim() === "" ? null : text });
      await onChanged();
      setStatus("saved");
    } catch (e: unknown) {
      setError(errorMessage(e, t("saveFailed")));
      setStatus("error");
    }
  }

  return (
    <Card>
      <CardHeader className="gap-2">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <CardTitle className="text-sm">{t("customTitle")}</CardTitle>
            <CardDescription className="mt-1 text-xs">{t("customDescription")}</CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Label htmlFor="custom-instructions-enabled" className="text-xs text-muted-foreground">
              {enabled ? t("on") : t("off")}
            </Label>
            <Switch
              id="custom-instructions-enabled"
              checked={enabled}
              disabled={!canEdit || busy}
              aria-label={t("enableCustom")}
              onCheckedChange={(v) => edit(() => setEnabled(v))}
            />
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Label htmlFor="custom-instructions-text" className="text-xs">
          {t("customLabel")}
        </Label>
        <Textarea
          id="custom-instructions-text"
          className="min-h-32 text-sm"
          value={text}
          readOnly={!canEdit}
          placeholder={t("customPlaceholder")}
          onChange={(e) => edit(() => setText(e.target.value))}
        />
        <div className="flex justify-end text-xs text-muted-foreground">
          <span className={tooLong ? "text-destructive" : undefined}>
            {t("charCount", { used: text.length, max: MAX_CUSTOM_INSTRUCTIONS_CHARS })}
          </span>
        </div>
        {status === "error" && error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        <div className="flex flex-wrap items-center gap-2">
          <Button type="button" size="sm" disabled={!canEdit || !dirty || busy || tooLong} onClick={save}>
            {busy ? t("saving") : t("save")}
          </Button>
          {status === "saved" && !dirty && (
            <span role="status" className="text-xs text-muted-foreground">
              {t("saved")}
            </span>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
