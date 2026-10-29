"use client";

import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Check, Loader2, Zap } from "lucide-react";
import { localModelsApi, type LocalModelSettings, type ReasoningEffort } from "../../lib/api/localModels";
import { errorMessage } from "../../lib/errors";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { Card } from "../ui/card";

const EFFORTS: ReasoningEffort[] = ["none", "low", "medium", "high", "default"];
const KEEP_ALIVES = ["5m", "30m", "2h", "-1"] as const;
// 0 = the server default; the others are made on demand as a variant of the model (no download).
const CONTEXTS = [0, 8192, 16384, 32768] as const;

/**
 * "Speed and behaviour" of the install's own local model: whether it thinks before answering, how
 * long it stays loaded, and the context window it actually runs with. Everything here used to be a
 * server setting; a person on a laptop has no .env to edit, so each one is a control. Saved on the
 * local model's row and applied from the next message.
 */
export default function LocalModelSettingsCard({ className }: { className?: string }) {
  const t = useTranslations("localModelSettings");
  const [settings, setSettings] = useState<LocalModelSettings | null>(null);
  const [state, setState] = useState<"loading" | "ready" | "none" | "error">("loading");
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    localModelsApi
      .getLocalModelSettings()
      .then((s) => {
        setSettings(s);
        setState(s.servingModel ? "ready" : "none");
      })
      .catch(() => setState("error"));
  }, []);
  useEffect(load, [load]);

  async function save(update: { reasoningEffort?: ReasoningEffort; keepAlive?: string; contextLength?: number }) {
    setSaving(true);
    setSaved(false);
    setError(null);
    try {
      setSettings(await localModelsApi.saveLocalModelSettings(update));
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err, t("saveFailed")));
    } finally {
      setSaving(false);
    }
  }

  if (state === "loading") {
    return (
      <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
        <Loader2 size={14} className="animate-spin" aria-hidden="true" />
        {t("loading")}
      </p>
    );
  }
  if (state !== "ready" || !settings) return null;

  const contextShort = settings.loadedContext != null && settings.loadedContext < settings.wantedContext;
  return (
    <Card className={cn("gap-4 p-5", className)} data-testid="local-model-settings">
      <div className="flex items-center gap-2">
        <Zap size={16} className="text-primary" aria-hidden="true" />
        <h2 className="text-sm font-semibold text-foreground">{t("title")}</h2>
        <span className="ms-auto truncate rounded-full border border-border px-2 py-0.5 text-[11px] text-muted-foreground">
          {settings.servingModel}
        </span>
      </div>

      <section aria-labelledby="lms-reasoning" className="space-y-1.5">
        <h3 id="lms-reasoning" className="text-xs font-medium text-foreground">
          {t("reasoningTitle")}
        </h3>
        <p className="text-xs text-muted-foreground">{t("reasoningHelp")}</p>
        <div role="radiogroup" aria-labelledby="lms-reasoning" className="flex flex-wrap gap-1.5">
          {EFFORTS.map((value) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={settings.reasoningEffort === value}
              disabled={saving}
              onClick={() => void save({ reasoningEffort: value })}
              data-testid={`effort-${value}`}
              className={cn(
                "h-8 rounded-full border px-3 text-xs transition-colors",
                settings.reasoningEffort === value
                  ? "border-primary bg-primary/10 text-foreground"
                  : "border-border text-muted-foreground hover:bg-secondary/60"
              )}
            >
              {t(`effort_${value}`)}
            </button>
          ))}
        </div>
      </section>

      <section aria-labelledby="lms-keep" className="space-y-1.5">
        <h3 id="lms-keep" className="text-xs font-medium text-foreground">
          {t("keepTitle")}
        </h3>
        <p className="text-xs text-muted-foreground">{t("keepHelp")}</p>
        <div role="radiogroup" aria-labelledby="lms-keep" className="flex flex-wrap gap-1.5">
          {KEEP_ALIVES.map((value) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={settings.keepAlive === value}
              disabled={saving}
              onClick={() => void save({ keepAlive: value })}
              data-testid={`keep-${value}`}
              className={cn(
                "h-8 rounded-full border px-3 text-xs transition-colors",
                settings.keepAlive === value
                  ? "border-primary bg-primary/10 text-foreground"
                  : "border-border text-muted-foreground hover:bg-secondary/60"
              )}
            >
              {t(`keep_${value}`)}
            </button>
          ))}
        </div>
      </section>

      <section className="space-y-1" aria-labelledby="lms-context">
        <h3 id="lms-context" className="text-xs font-medium text-foreground">
          {t("contextTitle")}
        </h3>
        <p className="text-xs text-muted-foreground">{t("contextHelp")}</p>
        <div role="radiogroup" aria-labelledby="lms-context" className="flex flex-wrap gap-1.5">
          {CONTEXTS.map((value) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={(settings.contextLength ?? 0) === value}
              disabled={saving}
              onClick={() => void save({ contextLength: value })}
              data-testid={`context-${value}`}
              className={cn(
                "h-8 rounded-full border px-3 text-xs transition-colors",
                (settings.contextLength ?? 0) === value
                  ? "border-primary bg-primary/10 text-foreground"
                  : "border-border text-muted-foreground hover:bg-secondary/60"
              )}
            >
              {value === 0 ? t("context_default") : t("context_n", { n: value.toLocaleString("en-US") })}
            </button>
          ))}
        </div>
        <p className="text-xs text-muted-foreground" data-testid="context-line">
          {settings.loadedContext == null
            ? t("contextNotLoaded", { wanted: settings.wantedContext })
            : t("contextLoaded", { loaded: settings.loadedContext, wanted: settings.wantedContext })}
        </p>
        {contextShort && (
          <p
            className="flex items-start gap-1.5 text-xs text-[var(--state-warning)]"
            role="note"
            data-testid="context-warning"
          >
            <AlertTriangle size={13} className="mt-0.5 shrink-0" aria-hidden="true" />
            <span>{t("contextShort", { wanted: settings.wantedContext.toLocaleString("en-US") })}</span>
          </p>
        )}
      </section>

      <div className="flex items-center gap-2 text-xs text-muted-foreground" aria-live="polite">
        {saving && (
          <>
            <Loader2 size={13} className="animate-spin" aria-hidden="true" />
            {t("saving")}
          </>
        )}
        {saved && !saving && (
          <>
            <Check size={13} aria-hidden="true" />
            {t("saved")}
          </>
        )}
        {error && (
          <span className="text-destructive" role="alert">
            {error}
          </span>
        )}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="ms-auto h-7 text-xs"
          onClick={load}
          disabled={saving}
        >
          {t("refresh")}
        </Button>
      </div>
    </Card>
  );
}
