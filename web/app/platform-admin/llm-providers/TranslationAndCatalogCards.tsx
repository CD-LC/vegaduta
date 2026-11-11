"use client";

import type { FormEvent } from "react";
import { motion } from "motion/react";
import { Info, Play } from "lucide-react";
import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Textarea } from "../components/ui/textarea";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { TONE_BOX } from "../components/admin/AdminKit";
import { PROVIDER_OPTIONS, withCurrentValue, type TranslationJobDraft } from "./constants";
import { useTranslations } from "../../lib/i18n";

// Translation-job reference settings + provider model catalog cards, extracted
// from page.tsx. State and handlers stay in the page; this is presentation.
export interface TranslationAndCatalogCardsProps {
  translationJob: any;
  translationJobError: string | null;
  translationDraft: TranslationJobDraft | null;
  setTranslationDraft: (updater: (d: any) => any) => void;
  savingTranslation: boolean;
  runningTranslationJob: boolean;
  handleRunTranslationJobNow: () => void;
  handleSaveTranslationJob: (e: FormEvent) => void;
  routingModelOptions: (providerName: string) => { value: any; label: string }[];
  routingModelSummary: (providerName: string, model: string) => string | null | undefined;
  loadingRoutingModelProviders: Record<string, boolean>;
  runningModelCatalog: boolean;
  handleRefreshModelCatalogNow: () => void;
  lastModelCatalogRefresh: string | number | Date | null;
}

export default function TranslationAndCatalogCards({
  translationJob,
  translationJobError,
  translationDraft,
  setTranslationDraft,
  savingTranslation,
  runningTranslationJob,
  handleRunTranslationJobNow,
  handleSaveTranslationJob,
  routingModelOptions,
  routingModelSummary,
  loadingRoutingModelProviders,
  runningModelCatalog,
  handleRefreshModelCatalogNow,
  lastModelCatalogRefresh,
}: TranslationAndCatalogCardsProps) {
  const tHC = useTranslations("hcPlatformadminLlmprovidersTranslationAndCatalogCards");
  return (
    <>
      {/* (b) Translation Job Reference Settings */}
      <motion.div
        className="mb-5"
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, delay: 0.05, ease: [0.16, 1, 0.3, 1] }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">{tHC("s19")}</CardTitle>
            <p className="text-xs text-muted-foreground">{tHC("s18")}</p>
          </CardHeader>
          <CardContent className="space-y-3">
            <Alert>
              <Info />
              <AlertDescription>
                {tHC("s17")} <strong>{tHC("s16")}</strong> {tHC("s15")} <strong>Run now</strong> {tHC("s14")}
              </AlertDescription>
            </Alert>

            {translationJobError && (
              <Alert variant="destructive">
                <AlertDescription>{translationJobError}</AlertDescription>
              </Alert>
            )}

            <div className="flex flex-wrap items-center gap-3">
              <Button onClick={handleRunTranslationJobNow} disabled={runningTranslationJob}>
                <Play size={14} />
                {runningTranslationJob ? "Dispatching…" : "Run translation job now"}
              </Button>
              {translationJob?.lastTriggeredAt && (
                <Badge
                  variant="outline"
                  className={translationJob.lastTriggerStatus === "DISPATCHED" ? TONE_BOX.ok : TONE_BOX.warn}
                  title={translationJob.lastTriggerMessage || ""}
                >
                  {tHC("s13")} {translationJob.lastTriggerStatus} -{" "}
                  {new Date(translationJob.lastTriggeredAt).toLocaleString()}
                  {translationJob.lastTriggeredBy ? ` (by ${translationJob.lastTriggeredBy})` : ""}
                </Badge>
              )}
            </div>
            {translationJob?.lastTriggerMessage && (
              <p className="text-xs text-muted-foreground">{translationJob.lastTriggerMessage}</p>
            )}

            {translationDraft && (
              <form onSubmit={handleSaveTranslationJob} className="space-y-3">
                <div className="flex flex-wrap items-end gap-3">
                  <div className="w-64 max-w-full space-y-1.5">
                    <Label htmlFor="translation-cron" className="text-xs text-muted-foreground">
                      {tHC("s12")}
                    </Label>
                    <Input
                      id="translation-cron"
                      value={translationDraft.cronScheduleDisplay}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, cronScheduleDisplay: e.target.value }))}
                      placeholder={tHC("s11")}
                    />
                  </div>
                  <div className="w-48 max-w-full space-y-1.5">
                    <Label htmlFor="translation-provider" className="text-xs text-muted-foreground">
                      Provider
                    </Label>
                    <Select
                      id="translation-provider"
                      searchable
                      value={translationDraft.providerName}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, providerName: e.target.value }))}
                      options={PROVIDER_OPTIONS}
                      placeholder={tHC("s10")}
                    />
                  </div>
                  <div className="w-56 max-w-full space-y-1.5">
                    <Label htmlFor="translation-model" className="text-xs text-muted-foreground">
                      Model
                    </Label>
                    <Select
                      id="translation-model"
                      searchable
                      allowCustom
                      value={translationDraft.model}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, model: e.target.value }))}
                      options={withCurrentValue(
                        routingModelOptions(translationDraft.providerName),
                        translationDraft.model
                      )}
                      placeholder={
                        loadingRoutingModelProviders[translationDraft.providerName]
                          ? "Loading models…"
                          : "Pick or type a model id…"
                      }
                    />
                    {routingModelSummary(translationDraft.providerName, translationDraft.model) && (
                      <p className="text-xs text-muted-foreground">
                        {routingModelSummary(translationDraft.providerName, translationDraft.model)}
                      </p>
                    )}
                  </div>
                  <div className="min-w-64 flex-1 space-y-1.5">
                    <Label htmlFor="translation-workflow-path" className="text-xs text-muted-foreground">
                      {tHC("s9")}
                    </Label>
                    <Input
                      id="translation-workflow-path"
                      value={translationDraft.workflowPathHint}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, workflowPathHint: e.target.value }))}
                      placeholder=".github/workflows/translate-messages.yml"
                    />
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="translation-notes" className="text-xs text-muted-foreground">
                    Notes
                  </Label>
                  <Textarea
                    id="translation-notes"
                    rows={3}
                    value={translationDraft.notes}
                    onChange={(e) => setTranslationDraft((d) => ({ ...d, notes: e.target.value }))}
                    placeholder={tHC("s8")}
                  />
                </div>
                <div className="flex items-center gap-3">
                  <Button type="submit" size="sm" disabled={savingTranslation}>
                    {savingTranslation ? "Saving…" : "Save"}
                  </Button>
                  {translationJob?.updatedAt && (
                    <span className="text-xs text-muted-foreground">
                      {tHC("s7")} {new Date(translationJob.updatedAt).toLocaleString()}
                      {translationJob.updatedBy ? ` by ${translationJob.updatedBy}` : ""}
                    </span>
                  )}
                </div>
              </form>
            )}
          </CardContent>
        </Card>
      </motion.div>

      {/* (b2) Provider Model Catalog */}
      <motion.div
        className="mb-5"
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, delay: 0.07, ease: [0.16, 1, 0.3, 1] }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="text-sm">{tHC("s6")}</CardTitle>
            <p className="text-xs text-muted-foreground">{tHC("s5")}</p>
          </CardHeader>
          <CardContent className="space-y-3">
            <Alert>
              <Info />
              <AlertDescription>
                {tHC("s4")} <strong>{tHC("s3")}</strong> {tHC("s2")}
              </AlertDescription>
            </Alert>

            <div className="flex flex-wrap items-center gap-3">
              <Button onClick={handleRefreshModelCatalogNow} disabled={runningModelCatalog}>
                <Play size={14} />
                {runningModelCatalog ? "Refreshing…" : "Refresh model catalog now"}
              </Button>
              {lastModelCatalogRefresh && (
                <Badge variant="outline" className={TONE_BOX.ok}>
                  {tHC("s1")} {new Date(lastModelCatalogRefresh).toLocaleString()}
                </Badge>
              )}
            </div>
          </CardContent>
        </Card>
      </motion.div>
    </>
  );
}
