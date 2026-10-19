"use client";

import { useEffect, useState, type ReactNode } from "react";
import { Trash2 } from "lucide-react";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Textarea } from "../components/ui/textarea";
import { useTranslations } from "../lib/i18n";

/**
 * One editable note (a fixed block or a named project note). `titleExtra`
 * sits beside the title (the "Active" badge) and `actions` to the left of
 * Clear/Save (the "Use in every chat" button) - both optional, so the fixed
 * blocks render exactly as before.
 */
export default function BlockEditor({
  label,
  title,
  hint,
  saved,
  maxChars,
  onSave,
  onClear,
  titleExtra,
  actions,
}: {
  label: string;
  title: string;
  hint?: string;
  saved: string;
  maxChars: number;
  onSave: (label: string, content: string) => Promise<unknown>;
  onClear: (label: string) => Promise<void>;
  titleExtra?: ReactNode;
  actions?: ReactNode;
}) {
  const t = useTranslations("memoryPage");
  const [draft, setDraft] = useState(saved);
  const [busy, setBusy] = useState(false);
  useEffect(() => setDraft(saved), [saved]);
  const dirty = draft !== saved;
  const over = draft.length > maxChars;

  return (
    <Card elevation="raised">
      <CardHeader>
        <CardTitle className="flex flex-wrap items-center gap-2">
          <span className="min-w-0 break-words">{title}</span>
          {titleExtra}
        </CardTitle>
        {hint && <CardDescription>{hint}</CardDescription>}
      </CardHeader>
      <CardContent className="space-y-2">
        <Textarea
          value={draft}
          rows={4}
          aria-label={title}
          onChange={(e) => setDraft(e.target.value)}
          placeholder={t("blockPlaceholder")}
        />
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className={`text-xs tabular-nums ${over ? "text-destructive" : "text-muted-foreground"}`}>
            {t("charCount", { count: draft.length, max: maxChars })}
          </span>
          <div className="flex flex-wrap gap-2">
            {actions}
            {saved && (
              <Button
                variant="outline"
                size="sm"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  try {
                    await onClear(label);
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                <Trash2 size={14} aria-hidden="true" /> {t("clear")}
              </Button>
            )}
            <Button
              size="sm"
              disabled={busy || !dirty || over}
              onClick={async () => {
                setBusy(true);
                try {
                  await onSave(label, draft);
                } finally {
                  setBusy(false);
                }
              }}
            >
              {t("save")}
            </Button>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
