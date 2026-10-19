"use client";

import { AnimatePresence, motion } from "motion/react";
import { KeyRound } from "lucide-react";
import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { TONE_BOX } from "../components/admin/AdminKit";
import { PROVIDER_OPTIONS, TASK_INFO, withCurrentValue, type LlmTaskDraft } from "./constants";

// LLM task-routing card, extracted from page.tsx. State and handlers stay in
// the page; this component is presentation only.
export interface TaskRoutingCardProps {
  llmTasks: any[];
  llmTasksError: string | null;
  taskDrafts: Record<string, LlmTaskDraft>;
  savingTask: string | null;
  updateDraft: (taskKey: string, patch: Partial<LlmTaskDraft>) => void;
  handleSaveTask: (taskKey: string) => void;
  routingModelOptions: (providerName: string) => { value: any; label: string }[];
  routingModelSummary: (providerName: string, model: string) => string | null | undefined;
  loadingRoutingModelProviders: Record<string, boolean>;
}

export default function TaskRoutingCard({
  llmTasks,
  llmTasksError,
  taskDrafts,
  savingTask,
  updateDraft,
  handleSaveTask,
  routingModelOptions,
  routingModelSummary,
  loadingRoutingModelProviders,
}: TaskRoutingCardProps) {
  return (
    <>
      {/* (a) LLM Task Routing */}
      <motion.div
        className="mb-5"
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, delay: 0.025, ease: [0.16, 1, 0.3, 1] }}
      >
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-sm">
              <KeyRound size={16} className="text-primary" /> LLM Task Routing
            </CardTitle>
            <p className="text-xs text-muted-foreground">
              Strictly internal, system-level tasks this platform itself uses an LLM for - not a tenant-facing default
              provider picker.
            </p>
          </CardHeader>
          <CardContent className="space-y-2">
            {llmTasksError && (
              <Alert variant="destructive">
                <AlertDescription>{llmTasksError}</AlertDescription>
              </Alert>
            )}

            <AnimatePresence initial={false}>
              {["TRANSLATION_REFRESH", "ASK_AI_HELPER"].map((taskKey) => {
                const row = llmTasks.find((t) => t.task === taskKey);
                const draft = taskDrafts[taskKey] || { providerName: "", model: "", apiKey: "" };
                const info = TASK_INFO[taskKey] || { label: taskKey, description: "" };
                return (
                  <motion.div
                    key={taskKey}
                    className="rounded-lg border border-border p-4"
                    initial={{ opacity: 0, y: 8 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ duration: 0.3 }}
                  >
                    <div className="mb-2 flex flex-wrap items-start justify-between gap-2">
                      <div>
                        <div className="text-sm font-semibold uppercase tracking-wide text-foreground">{info.label}</div>
                        <p className="mt-0.5 text-xs text-muted-foreground">{info.description}</p>
                      </div>
                      <Badge variant={row?.hasCredential ? "outline" : "secondary"} className={row?.hasCredential ? TONE_BOX.ok : undefined}>
                        {row?.hasCredential ? "key configured" : "no key set"}
                      </Badge>
                    </div>

                    <div className="flex flex-wrap items-end gap-3">
                      <div className="w-48 space-y-1.5">
                        <Label htmlFor={`task-provider-${taskKey}`} className="text-xs text-muted-foreground">Provider</Label>
                        <Select
                          id={`task-provider-${taskKey}`}
                          searchable
                          value={draft.providerName}
                          onChange={(e) => updateDraft(taskKey, { providerName: e.target.value })}
                          options={PROVIDER_OPTIONS}
                          placeholder="Select a provider…"
                        />
                      </div>
                      <div className="w-56 space-y-1.5">
                        <Label htmlFor={`task-model-${taskKey}`} className="text-xs text-muted-foreground">Model</Label>
                        <Select
                          id={`task-model-${taskKey}`}
                          searchable
                          allowCustom
                          value={draft.model}
                          onChange={(e) => updateDraft(taskKey, { model: e.target.value })}
                          options={withCurrentValue(routingModelOptions(draft.providerName), draft.model)}
                          placeholder={
                            loadingRoutingModelProviders[draft.providerName]
                              ? "Loading models…"
                              : "e.g. claude-sonnet-4-5"
                          }
                        />
                        {routingModelSummary(draft.providerName, draft.model) && (
                          <p className="text-xs text-muted-foreground">
                            {routingModelSummary(draft.providerName, draft.model)}
                          </p>
                        )}
                      </div>
                      <div className="w-56 space-y-1.5">
                        <Label htmlFor={`task-apikey-${taskKey}`} className="text-xs text-muted-foreground">New API key</Label>
                        <Input
                          id={`task-apikey-${taskKey}`}
                          type="password"
                          value={draft.apiKey}
                          onChange={(e) => updateDraft(taskKey, { apiKey: e.target.value })}
                          placeholder="Leave blank to keep the current key"
                          autoComplete="new-password"
                        />
                      </div>
                      <Button size="sm" disabled={savingTask === taskKey} onClick={() => handleSaveTask(taskKey)}>
                        {savingTask === taskKey ? "Saving…" : "Save"}
                      </Button>
                    </div>
                    {row?.lastValidatedAt && (
                      <div className="mt-2 font-mono text-xs text-muted-foreground">
                        Last validated {new Date(row.lastValidatedAt).toLocaleString()}
                      </div>
                    )}
                  </motion.div>
                );
              })}
            </AnimatePresence>
          </CardContent>
        </Card>
      </motion.div>

    </>
  );
}
