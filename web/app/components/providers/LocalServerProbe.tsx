"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";

type ProbeResult = Awaited<ReturnType<typeof api.probeLocalOpenAi>>;

/**
 * On the connected "Local / OpenAI-compatible server" card: pick one of the server's own models and check whether
 * it makes tool calls. Small local models are the usual reason an agent "does nothing"; this says so before the
 * first chat and what to change on the server. Advisory - it changes nothing. Callers gate on admin + ACTIVE.
 */
export default function LocalServerProbe() {
  const t = useTranslations("providersPanel");
  const [models, setModels] = useState<string[]>([]);
  const [model, setModel] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ProbeResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listProviderModels("LOCAL_OPENAI")
      .then((list) => {
        if (cancelled || !Array.isArray(list)) return;
        const ids = list.map((m) => m.modelId);
        setModels(ids);
        setModel((current) => current || ids[0] || "");
      })
      .catch(() => {
        // The list is a convenience; the free-text field below still works.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function check() {
    if (!model.trim() || busy) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.probeLocalOpenAi(model.trim()));
    } catch (err) {
      setError(errorMessage(err, t("localOpenaiProbeFailedError")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-4 space-y-2" role="presentation" onClick={(e) => e.stopPropagation()}>
      <p className="text-xs text-muted-foreground">{t("localOpenaiProbeNote")}</p>
      <div className="flex flex-wrap items-center gap-2">
        {models.length > 0 ? (
          <Select
            aria-label={t("localOpenaiProbeModelLabel")}
            options={models.map((id) => ({ value: id, label: id }))}
            value={model}
            onValueChange={setModel}
            className="min-w-[min(220px,100%)] flex-1"
          />
        ) : (
          <Input
            aria-label={t("localOpenaiProbeModelLabel")}
            placeholder={t("localOpenaiProbeModelPlaceholder")}
            value={model}
            onChange={(e) => setModel(e.target.value)}
            className="min-w-[min(220px,100%)] flex-1"
          />
        )}
        <Button type="button" size="sm" variant="outline" disabled={busy || !model.trim()} onClick={check}>
          {busy ? t("localOpenaiProbeRunning") : t("localOpenaiProbeButton")}
        </Button>
      </div>
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
      {result && (
        <p role="status" className="text-xs text-foreground" data-grade={result.toolCallGrade ?? "UNKNOWN"}>
          {result.advice}
        </p>
      )}
    </div>
  );
}
