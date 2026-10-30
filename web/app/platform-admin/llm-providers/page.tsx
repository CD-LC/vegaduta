"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { RefreshCw } from "lucide-react";
import "../components/dashboard/dashboardStyles";
import { api } from "../lib/api";
import AdminShell from "../components/AdminShell";
import { PageHint } from "../components/sections/common";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { useConfirm } from "../components/admin/ConfirmDialog";
import TaskRoutingCard from "./TaskRoutingCard";
import TranslationAndCatalogCards from "./TranslationAndCatalogCards";
import { HostedPoolCard } from "./HostedPoolCard";
import { useHostedPool } from "./useHostedPool";
import { TASK_INFO, type LlmTaskDraft, type TranslationJobDraft } from "./constants";
import { useTranslations } from "../../lib/i18n";

// LLM & Providers (PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §3.2) - the hosted
// pool, per-task model routing, the model catalog, and the translation job,
// split out of the old Ecosystem Maintenance monolith. The operational
// rate-limit settings from that screen moved to Abuse & Rate Control, the
// cross-tenant roster + feature flags to Tenants & Users, and the shared-infra
// health readout to Overview / Diagnostics. Behaviour and endpoints for the
// four provider/LLM cards below are unchanged from the original screen.

export default function LlmProvidersPage() {
    const tHC = useTranslations("hcPlatformadminLlmprovidersPage");
  const askConfirm = useConfirm();
  const [llmTasks, setLlmTasks] = useState([]);
  const [llmTasksError, setLlmTasksError] = useState(null);
  const [taskDrafts, setTaskDrafts] = useState<Record<string, LlmTaskDraft>>({}); // task -> { providerName, model, apiKey }
  const [savingTask, setSavingTask] = useState(null);

  const [translationJob, setTranslationJob] = useState(null);
  const [translationJobError, setTranslationJobError] = useState(null);
  const [translationDraft, setTranslationDraft] = useState<TranslationJobDraft | null>(null);
  const [savingTranslation, setSavingTranslation] = useState(false);
  const [runningTranslationJob, setRunningTranslationJob] = useState(false);

  const [runningModelCatalog, setRunningModelCatalog] = useState(false);
  const [lastModelCatalogRefresh, setLastModelCatalogRefresh] = useState(null);

  const pool = useHostedPool(askConfirm);
  const { setHostedProviders, setHostedProvidersError, setPoolSettings } = pool;

  // Cached model catalogs for the task-routing + translation-job model
  // dropdowns, keyed by providerName (the two task rows and the translation
  // job can each point at a different provider). Same cached-catalog source
  // as the hosted-pool "provider" mode in useHostedPool.ts (GET /api/providers/{p}/models).
  const [routingModelsByProvider, setRoutingModelsByProvider] = useState<Record<string, any[]>>({}); // providerName -> ProviderModelInfo[]
  const [loadingRoutingModelProviders, setLoadingRoutingModelProviders] = useState<Record<string, boolean>>({}); // providerName -> true

  const [refreshing, setRefreshing] = useState(false);
  const [lastChecked, setLastChecked] = useState(null);

  const refresh = useCallback(async () => {
    setRefreshing(true);

    try {
      const list = await api.getPlatformLlmTasks();
      setLlmTasks(list);
      setLlmTasksError(null);
      setTaskDrafts((prev) => {
        const next = { ...prev };
        for (const row of list) {
          if (!next[row.task]) {
            next[row.task] = { providerName: row.providerName || "", model: row.model || "", apiKey: "" };
          }
        }
        return next;
      });
    } catch (err) {
      setLlmTasks([]);
      setLlmTasksError((err as Error).message);
    }

    try {
      const settings = await api.getPlatformTranslationJob();
      setTranslationJob(settings);
      setTranslationJobError(null);
      setTranslationDraft({
        cronScheduleDisplay: settings.cronScheduleDisplay || "",
        providerName: settings.providerName || "",
        model: settings.model || "",
        workflowPathHint: settings.workflowPathHint || "",
        notes: settings.notes || "",
      });
    } catch (err) {
      setTranslationJob(null);
      setTranslationJobError((err as Error).message);
    }

    try {
      setHostedProviders(await api.getPlatformHostedLlmProviders());
      setHostedProvidersError(null);
    } catch (err) {
      setHostedProviders([]);
      setHostedProvidersError((err as Error).message);
    }

    try {
      setPoolSettings(await api.getHostedLlmPoolSettings());
    } catch {
      // Non-critical - the per-row toggles/list above still work either way;
      // just leave the master switch unrendered rather than blocking the page.
    }

    setLastChecked(new Date());
    setRefreshing(false);
  }, [setHostedProviders, setHostedProvidersError, setPoolSettings]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  function updateDraft(task: string, patch: Partial<LlmTaskDraft>) {
    setTaskDrafts((prev) => ({ ...prev, [task]: { ...prev[task], ...patch } }));
  }

  async function handleSaveTask(task: string) {
    const draft: Partial<LlmTaskDraft> = taskDrafts[task] || {};
    if (!draft.providerName) {
      toast.error("Pick a provider before saving.");
      return;
    }
    setSavingTask(task);
    try {
      const updated = await api.updatePlatformLlmTask(task, {
        providerName: draft.providerName,
        model: draft.model || null,
        apiKey: draft.apiKey ? draft.apiKey : null,
      });
      setLlmTasks((prev) => prev.map((row) => (row.task === task ? updated : row)));
      updateDraft(task, { apiKey: "" }); // never keep a typed key in memory after a successful save
      toast.success(`${TASK_INFO[task]?.label || task} routing updated.`);
    } catch (err) {
      toast.error((err as Error).message || `Could not update ${task}.`);
    } finally {
      setSavingTask(null);
    }
  }

  async function handleSaveTranslationJob(e: FormEvent) {
    e.preventDefault();
    if (!translationDraft) return;
    setSavingTranslation(true);
    try {
      const updated = await api.updatePlatformTranslationJob(translationDraft);
      setTranslationJob(updated);
      toast.success("Translation job reference settings saved.");
    } catch (err) {
      toast.error((err as Error).message || "Could not save translation job settings.");
    } finally {
      setSavingTranslation(false);
    }
  }

  async function handleRunTranslationJobNow() {
    setRunningTranslationJob(true);
    try {
      const updated = await api.runPlatformTranslationJobNow();
      setTranslationJob(updated);
      if (updated.lastTriggerStatus === "DISPATCHED") {
        toast.success("Translation job dispatched - check the GitHub Actions tab for progress.");
      } else if (updated.lastTriggerStatus === "NOT_CONFIGURED") {
        toast.error(updated.lastTriggerMessage || "Translation job isn't configured for on-demand runs yet.");
      } else {
        toast.error(updated.lastTriggerMessage || "Could not dispatch the translation job.");
      }
    } catch (err) {
      toast.error((err as Error).message || "Could not dispatch the translation job.");
    } finally {
      setRunningTranslationJob(false);
    }
  }

  async function handleRefreshModelCatalogNow() {
    setRunningModelCatalog(true);
    try {
      const result = await api.refreshPlatformModelCatalogNow();
      setLastModelCatalogRefresh(result.refreshedAt);
      toast.success("Model catalog refreshed - new/updated models are live in every provider's dropdown now.");
    } catch (err) {
      toast.error((err as Error).message || "Could not refresh the model catalog.");
    } finally {
      setRunningModelCatalog(false);
    }
  }

  // Fetches (once per provider, cached for the page's lifetime) the model
  // catalog for whichever providers the task-routing rows / translation job
  // currently point at, so those Model fields can be dropdowns instead of
  // blind free-text. A provider whose fetch fails (or that has no catalog)
  // caches [] - the Select's allowCustom escape hatch still lets any model
  // id be typed, so nothing is ever blocked on the catalog being available.
  useEffect(() => {
    const wanted = new Set(
      [
        taskDrafts["TRANSLATION_REFRESH"]?.providerName,
        taskDrafts["ASK_AI_HELPER"]?.providerName,
        translationDraft?.providerName,
      ].filter(Boolean)
    );
    wanted.forEach((provider) => {
      if (routingModelsByProvider[provider] !== undefined || loadingRoutingModelProviders[provider]) return;
      setLoadingRoutingModelProviders((prev) => ({ ...prev, [provider]: true }));
      api
        .listProviderModels(provider)
        .then((models) => setRoutingModelsByProvider((prev) => ({ ...prev, [provider]: models || [] })))
        .catch(() => setRoutingModelsByProvider((prev) => ({ ...prev, [provider]: [] })))
        .finally(() =>
          setLoadingRoutingModelProviders((prev) => {
            const next = { ...prev };
            delete next[provider];
            return next;
          })
        );
    });
  }, [taskDrafts, translationDraft, routingModelsByProvider, loadingRoutingModelProviders]);

  function routingModelOptions(provider: string) {
    return (routingModelsByProvider[provider] || []).map((m) => ({
      value: m.modelId,
      label: m.displayName && m.displayName !== m.modelId ? `${m.displayName} (${m.modelId})` : m.modelId,
    }));
  }

  function routingModelSummary(provider: string, modelId: string) {
    if (!provider || !modelId) return null;
    const row = (routingModelsByProvider[provider] || []).find((m) => m.modelId === modelId);
    return row?.capabilitySummary || null;
  }


  return (
    <AdminShell
      title={tHC("s3")}
      actions={
        <Button variant="outline" size="sm" onClick={refresh} disabled={refreshing} title={tHC("s2")}>
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        {tHC("s1")}
        {lastChecked && ` Last checked ${lastChecked.toLocaleTimeString()}.`}
      </PageHint>

      <TaskRoutingCard
        llmTasks={llmTasks}
        llmTasksError={llmTasksError}
        taskDrafts={taskDrafts}
        savingTask={savingTask}
        updateDraft={updateDraft}
        handleSaveTask={handleSaveTask}
        routingModelOptions={routingModelOptions}
        routingModelSummary={routingModelSummary}
        loadingRoutingModelProviders={loadingRoutingModelProviders}
      />
      {/* (a2) Platform-Hosted LLM Provider Pool (FREE-tier, runtime-configurable) */}
      <HostedPoolCard pool={pool} />

      <TranslationAndCatalogCards
        translationJob={translationJob}
        translationJobError={translationJobError}
        translationDraft={translationDraft}
        setTranslationDraft={setTranslationDraft}
        savingTranslation={savingTranslation}
        runningTranslationJob={runningTranslationJob}
        handleRunTranslationJobNow={handleRunTranslationJobNow}
        handleSaveTranslationJob={handleSaveTranslationJob}
        routingModelOptions={routingModelOptions}
        routingModelSummary={routingModelSummary}
        loadingRoutingModelProviders={loadingRoutingModelProviders}
        runningModelCatalog={runningModelCatalog}
        handleRefreshModelCatalogNow={handleRefreshModelCatalogNow}
        lastModelCatalogRefresh={lastModelCatalogRefresh}
      />
    </AdminShell>
  );
}
