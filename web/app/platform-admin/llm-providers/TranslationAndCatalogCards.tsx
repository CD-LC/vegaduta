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
            <CardTitle className="text-sm">Translation Job Reference Settings</CardTitle>
            <p className="text-xs text-muted-foreground">Weekly i18n refresh, owned by a GitHub Actions workflow</p>
          </CardHeader>
          <CardContent className="space-y-3">
            <Alert>
              <Info />
              <AlertDescription>
                The weekly translation refresh is owned by the GitHub Actions workflow at the path shown below, running
                on its own cron schedule with its own repository secrets. The fields in this form (schedule/provider/
                model/notes) are <strong>reference only</strong> - saving them does not change the workflow itself, and
                editing the workflow directly in GitHub doesn&apos;t update these fields automatically. The{" "}
                <strong>Run now</strong> button below is the one control on this form with a real effect: it dispatches
                that workflow immediately instead of waiting for Monday.
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
                  className={
                    translationJob.lastTriggerStatus === "DISPATCHED"
                      ? TONE_BOX.ok
                      : TONE_BOX.warn
                  }
                  title={translationJob.lastTriggerMessage || ""}
                >
                  Last run: {translationJob.lastTriggerStatus} - {new Date(translationJob.lastTriggeredAt).toLocaleString()}
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
                    <Label htmlFor="translation-cron" className="text-xs text-muted-foreground">Cron schedule (display)</Label>
                    <Input id="translation-cron"
                      value={translationDraft.cronScheduleDisplay}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, cronScheduleDisplay: e.target.value }))}
                      placeholder="e.g. Every Monday, 06:00 UTC"
                    />
                  </div>
                  <div className="w-48 max-w-full space-y-1.5">
                    <Label htmlFor="translation-provider" className="text-xs text-muted-foreground">Provider</Label>
                    <Select id="translation-provider"
                      searchable
                      value={translationDraft.providerName}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, providerName: e.target.value }))}
                      options={PROVIDER_OPTIONS}
                      placeholder="Select a provider…"
                    />
                  </div>
                  <div className="w-56 max-w-full space-y-1.5">
                    <Label htmlFor="translation-model" className="text-xs text-muted-foreground">Model</Label>
                    <Select id="translation-model"
                      searchable
                      allowCustom
                      value={translationDraft.model}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, model: e.target.value }))}
                      options={withCurrentValue(routingModelOptions(translationDraft.providerName), translationDraft.model)}
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
                    <Label htmlFor="translation-workflow-path" className="text-xs text-muted-foreground">Workflow path hint</Label>
                    <Input id="translation-workflow-path"
                      value={translationDraft.workflowPathHint}
                      onChange={(e) => setTranslationDraft((d) => ({ ...d, workflowPathHint: e.target.value }))}
                      placeholder=".github/workflows/translate-messages.yml"
                    />
                  </div>
                </div>
                <div className="space-y-1.5">
                  <Label htmlFor="translation-notes" className="text-xs text-muted-foreground">Notes</Label>
                  <Textarea id="translation-notes"
                    rows={3}
                    value={translationDraft.notes}
                    onChange={(e) => setTranslationDraft((d) => ({ ...d, notes: e.target.value }))}
                    placeholder="Anything future admins should know about this workflow's repo secrets/config."
                  />
                </div>
                <div className="flex items-center gap-3">
                  <Button type="submit" size="sm" disabled={savingTranslation}>
                    {savingTranslation ? "Saving…" : "Save"}
                  </Button>
                  {translationJob?.updatedAt && (
                    <span className="text-xs text-muted-foreground">
                      Last updated {new Date(translationJob.updatedAt).toLocaleString()}
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
            <CardTitle className="text-sm">Provider Model Catalog</CardTitle>
            <p className="text-xs text-muted-foreground">Backs every provider&apos;s model dropdown on the Agents page</p>
          </CardHeader>
          <CardContent className="space-y-3">
            <Alert>
              <Info />
              <AlertDescription>
                Runs daily at 03:00 UTC. OpenRouter is pulled live from its public model list; Anthropic/Gemini/Grok/
                Meta/Sarvam are refreshed from a hand-maintained seed list (their real model endpoints require a BYOK key
                this platform-wide job doesn&apos;t have). After hand-editing that seed list in code, or if
                OpenRouter&apos;s live catalog just changed, use <strong>Refresh now</strong> instead of waiting for the
                next 03:00 UTC run.
              </AlertDescription>
            </Alert>

            <div className="flex flex-wrap items-center gap-3">
              <Button onClick={handleRefreshModelCatalogNow} disabled={runningModelCatalog}>
                <Play size={14} />
                {runningModelCatalog ? "Refreshing…" : "Refresh model catalog now"}
              </Button>
              {lastModelCatalogRefresh && (
                <Badge variant="outline" className={TONE_BOX.ok}>
                  Last refreshed: {new Date(lastModelCatalogRefresh).toLocaleString()}
                </Badge>
              )}
            </div>
          </CardContent>
        </Card>
      </motion.div>
    </>
  );
}
