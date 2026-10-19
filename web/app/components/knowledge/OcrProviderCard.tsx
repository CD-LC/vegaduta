"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import Select from "../Select";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Input } from "../ui/input";

// OCR providers advertised in the UI but with no server implementation yet -
// sidecar/app/extraction.py silently falls back to Tesseract for both, so they
// must never be selectable or saveable. Rendered as greyed-out "coming soon"
// rows below the provider Select (same pattern as
// agents/TranscriptionProviderOptions' disabledProviders) instead of options.
const COMING_SOON_OCR_PROVIDERS = ["AWS_TEXTRACT", "AZURE_DOCUMENT_INTELLIGENCE"];

/**
 * Tenant-level OCR configuration (BYOK cloud OCR for image ingestion). null provider = unconfigured,
 * the expected working default (the sidecar falls back to Tesseract), not an error state.
 */
export default function OcrProviderCard() {
  const t = useTranslations("knowledgePanel");
  const [ocrProvider, setOcrProvider] = useState<string | null>(null);
  const [ocrPanelOpen, setOcrPanelOpen] = useState(false);
  const [ocrProviderChoice, setOcrProviderChoice] = useState("GOOGLE_VISION");
  const [ocrApiKey, setOcrApiKey] = useState("");
  const [ocrBusy, setOcrBusy] = useState(false);
  const [ocrError, setOcrError] = useState<string | null>(null);

  const refreshOcrProvider = useCallback(() => {
    api
      .getOcrProviderStatus()
      .then((res) => setOcrProvider(res?.provider || null))
      .catch(() => setOcrProvider(null));
  }, []);

  useEffect(() => {
    refreshOcrProvider();
  }, [refreshOcrProvider]);

  async function handleActivateOcr(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!ocrApiKey.trim()) return;
    // Belt-and-braces: coming-soon providers are no longer Select options, but
    // guard the save path too so they can never be activated.
    if (COMING_SOON_OCR_PROVIDERS.includes(ocrProviderChoice)) return;
    setOcrBusy(true);
    setOcrError(null);
    try {
      await api.activateOcrProvider(ocrProviderChoice, ocrApiKey.trim());
      setOcrApiKey("");
      setOcrPanelOpen(false);
      refreshOcrProvider();
    } catch (err) {
      setOcrError(errorMessage(err, t("ocrActivateError")));
    } finally {
      setOcrBusy(false);
    }
  }

  async function handleDeactivateOcr() {
    setOcrBusy(true);
    setOcrError(null);
    try {
      await api.deactivateOcrProvider();
      refreshOcrProvider();
    } catch (err) {
      setOcrError(errorMessage(err, t("ocrDeactivateError")));
    } finally {
      setOcrBusy(false);
    }
  }

  return (
    <Card className="gap-0 p-5">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("ocrProviderLabel")}
        </span>
        {!ocrPanelOpen && (
          <Button type="button" variant="outline" size="sm" onClick={() => setOcrPanelOpen(true)}>
            {ocrProvider ? t("changeButton") : t("configureButton")}
          </Button>
        )}
      </div>
      {ocrError && (
        <div
          role="alert"
          className="mt-3 rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)]"
        >
          {ocrError}
        </div>
      )}
      {!ocrPanelOpen ? (
        <div className="mt-3 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          {ocrProvider ? (
            <>
              <strong className="text-foreground">{ocrProvider.replace("_", " ")}</strong> {t("ocrActiveSuffix")}{" "}
              <Button type="button" variant="outline" size="sm" onClick={handleDeactivateOcr} disabled={ocrBusy}>
                {t("deactivateButton")}
              </Button>
            </>
          ) : (
            t("ocrDefaultDescription")
          )}
        </div>
      ) : (
        <form onSubmit={handleActivateOcr} className="mt-3 flex flex-wrap items-center gap-2">
          <Select
            value={ocrProviderChoice}
            onChange={(e) => setOcrProviderChoice(e.target.value)}
            options={[{ value: "GOOGLE_VISION", label: t("googleVisionOption") }]}
          />
          <label htmlFor="ocr-api-key" className="sr-only">
            {t("apiKeyPlaceholder")}
          </label>
          <Input
            id="ocr-api-key"
            type="password"
            placeholder={t("apiKeyPlaceholder")}
            value={ocrApiKey}
            onChange={(e) => setOcrApiKey(e.target.value)}
            className="min-w-40 flex-1"
          />
          <Button type="submit" size="sm" disabled={ocrBusy || !ocrApiKey.trim()}>
            {ocrBusy ? t("savingButton") : t("saveButton")}
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={() => setOcrPanelOpen(false)}>
            {t("cancelButton")}
          </Button>
          {/* Coming-soon OCR providers: greyed-out, non-interactive rows
              (same treatment as TranscriptionProviderOptions' disabled
              providers) - visible so a tenant can see they're planned, but
              never selectable, since the sidecar has no implementation and
              would silently fall back to Tesseract. */}
          {[t("awsTextractOption"), t("azureDocIntelOption")].map((label) => (
            <div
              key={label}
              className="basis-full flex items-center justify-between gap-2 rounded-md border border-dashed px-3 py-2 text-sm text-muted-foreground opacity-60"
            >
              {label}
              <Badge variant="secondary">{t("comingSoonBadge")}</Badge>
            </div>
          ))}
        </form>
      )}
    </Card>
  );
}
