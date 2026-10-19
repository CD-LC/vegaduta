"use client";

import { useCallback, useEffect, useState } from "react";
import { ChevronDown, ChevronUp, GitCompare, Loader2, Undo2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../ui/alert-dialog";
import AgentVersionActionDialog, { type GateReport as DialogGate, type VersionDialogMode } from "./AgentVersionActionDialog";
import { Badge } from "../ui/badge";

// Agent configuration versions (AgentVersionController, V212 + V260 eval
// gate) - snapshots are written SERVER-SIDE on every save, so this card is
// list / inspect / compare / promote / rollback / restore / label; there is no "save a snapshot"
// button by design.
//
// Two semantics the UI copy must not blur (AgentVersionLabel's javadoc):
// a label is a POINTER ("this is the snapshot we consider good"), it does
// not deploy anything - RESTORE is the only action that changes the live
// agent, and it appends a fresh version row, which is what makes a restore
// itself undoable.
//
// Self-contained like AgentGuardrailsCard/AgentSkillsCard: own fetch, own
// busy/error state. `locked` + `editLockedTooltip` come from the workbench
// because the FREE-tier edit-lock is a per-control obligation there, not
// something a child can infer.

type VersionLabel = "STAGING" | "PRODUCTION";

interface AgentVersionRow {
  id: string;
  agentId: string;
  versionNumber: number;
  agentName: string;
  providerName: string;
  model: string | null;
  changeSummary: string | null;
  label: VersionLabel | null;
  createdBy: string | null;
  createdAt: string;
}

export default function AgentVersionsCard({
  agentId,
  locked,
  editLockedTooltip,
  onRestored,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
  /** Called after a successful restore so the workbench re-reads the agent
   * (the restore response IS the new AgentResponse, but the parent's
   * onChanged refresh is the single refresh path every other save uses). */
  onRestored?: () => void;
}) {
  const t = useTranslations("agentVersionsCard");
  const tu = useTranslations("agentsUi");
  const [versions, setVersions] = useState<AgentVersionRow[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [expandedConfig, setExpandedConfig] = useState<any | "loading" | "error" | null>(null);
  // Diff / promote / rollback dialog (one at a time). `gate` is the eval-gate
  // report a 412 returned, which reveals the force override inside the dialog.
  const [dialog, setDialog] = useState<{
    mode: VersionDialogMode;
    version: AgentVersionRow;
    from: number | null;
  } | null>(null);
  const [gate, setGate] = useState<DialogGate | null>(null);
  const [dialogError, setDialogError] = useState<string | null>(null);

  const load = useCallback(async (): Promise<void> => {
    const rows: AgentVersionRow[] = await api.listAgentVersions(agentId);
    setVersions(rows);
  }, [agentId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api
      .listAgentVersions(agentId)
      .then((rows: AgentVersionRow[]) => {
        if (!cancelled) setVersions(rows || []);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : t("loadFailed"));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId, t]);

  async function toggleExpanded(versionId: string) {
    if (expandedId === versionId) {
      setExpandedId(null);
      setExpandedConfig(null);
      return;
    }
    setExpandedId(versionId);
    setExpandedConfig("loading");
    try {
      const full = await api.getAgentVersion(agentId, versionId);
      setExpandedConfig(full.config || null);
    } catch {
      setExpandedConfig("error");
    }
  }

  async function restore(versionId: string) {
    setBusyId(versionId);
    setError(null);
    setNotice(null);
    try {
      await api.restoreAgentVersion(agentId, versionId);
      setNotice(t("restoredNotice"));
      onRestored?.();
      await load().catch((): void => undefined);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t("restoreError"));
    } finally {
      setBusyId(null);
    }
  }

  async function setLabel(versionId: string, label: VersionLabel | null) {
    setBusyId(versionId);
    setError(null);
    setNotice(null);
    try {
      await api.setAgentVersionLabel(agentId, versionId, label);
      setNotice(t("labelUpdatedNotice"));
      await load().catch((): void => undefined);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t("labelError"));
    } finally {
      setBusyId(null);
    }
  }

  const productionVersion = versions?.find((v) => v.label === "PRODUCTION") ?? null;

  function openDialog(mode: VersionDialogMode, version: AgentVersionRow) {
    setGate(null);
    setDialogError(null);
    // promote/compare: against the live PRODUCTION version; rollback: against
    // the version just below it (the one PRODUCTION would move back to).
    let from: number | null = null;
    if (mode === "rollback") {
      const lower = (versions ?? []).filter((x) => x.versionNumber < version.versionNumber);
      from = lower.length ? Math.max(...lower.map((x) => x.versionNumber)) : null;
    } else if (productionVersion && productionVersion.id !== version.id) {
      from = productionVersion.versionNumber;
    }
    setDialog({ mode, version, from });
  }

  async function confirmDialog(opts: { force: boolean; reason: string }) {
    if (!dialog) return;
    const { mode, version } = dialog;
    setBusyId(version.id);
    setDialogError(null);
    setError(null);
    try {
      if (mode === "rollback") {
        await api.rollbackAgentVersion(agentId);
        setNotice(tu("rolledBackNotice"));
      } else {
        await api.promoteAgentVersion(agentId, version.id, "PRODUCTION", {
          force: opts.force || undefined,
          reason: opts.reason || undefined,
        });
        setNotice(t("promotedNotice"));
      }
      setDialog(null);
      await load().catch((): void => undefined);
    } catch (e: any) {
      // 412 = the eval gate refused this promote; the body is the report.
      if (e?.status === 412 && mode === "promote") {
        setGate(e.gate || {});
      } else {
        setDialogError(e instanceof Error ? e.message : t("labelError"));
      }
    } finally {
      setBusyId(null);
    }
  }

  const anyBusy = busyId !== null;

  return (
    <div className="mb-4">
      <p className="mb-2 text-xs text-muted-foreground">{t("explainer")}</p>

      {loading && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          {t("loadingVersions")}
        </div>
      )}

      {!loading && error && <div className="ax-kb-error mb-2">{error}</div>}
      {!loading && notice && <div className="mb-2 text-xs text-foreground" role="status">{notice}</div>}

      {!loading && versions && versions.length === 0 && (
        <p className="text-xs text-muted-foreground">{t("emptyVersions")}</p>
      )}

      {!loading && versions && versions.length > 0 && (
        <div className="space-y-2">
          {versions.map((v) => (
            <div key={v.id} className="rounded-lg border border-border bg-card px-3 py-2">
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="font-mono text-xs font-semibold text-foreground">v{v.versionNumber}</span>
                    {v.label && (
                      <Badge
                        variant="state"
                        state={v.label === "PRODUCTION" ? "var(--state-success)" : "var(--state-warning)"}
                      >
                        {v.label === "PRODUCTION" ? t("productionLabel") : t("stagingLabel")}
                      </Badge>
                    )}
                    <span className="truncate text-xs text-muted-foreground">
                      {v.providerName}
                      {v.model ? ` / ${v.model}` : ""}
                    </span>
                  </div>
                  {v.changeSummary && (
                    <div className="mt-0.5 truncate text-xs text-muted-foreground">{v.changeSummary}</div>
                  )}
                  <div className="mt-0.5 text-[11px] text-muted-foreground">
                    {new Date(v.createdAt).toLocaleString()}
                    {v.createdBy ? ` · ${v.createdBy}` : ""}
                  </div>
                </div>

                <div className="flex shrink-0 flex-wrap items-center justify-end gap-1">
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="h-7 px-2 text-xs"
                    onClick={() => void toggleExpanded(v.id)}
                    aria-expanded={expandedId === v.id}
                  >
                    {expandedId === v.id ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
                    {t("detailsButton")}
                  </Button>
                  {v.label !== "STAGING" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-7 px-2 text-xs"
                      disabled={locked || anyBusy}
                      title={locked ? editLockedTooltip : t("markStagingTooltip")}
                      onClick={() => void setLabel(v.id, "STAGING")}
                    >
                      {t("markStaging")}
                    </Button>
                  )}
                  {v.label !== "PRODUCTION" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-7 px-2 text-xs"
                      disabled={locked || anyBusy}
                      title={locked ? editLockedTooltip : t("promoteTooltip")}
                      onClick={() => openDialog("promote", v)}
                    >
                      {t("promoteProduction")}
                    </Button>
                  )}
                  {v.label && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="h-7 px-2 text-xs text-muted-foreground"
                      disabled={locked || anyBusy}
                      title={locked ? editLockedTooltip : t("clearLabelTooltip")}
                      onClick={() => void setLabel(v.id, null)}
                    >
                      {t("clearLabel")}
                    </Button>
                  )}
                  {productionVersion && productionVersion.id !== v.id && (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="h-7 px-2 text-xs"
                      onClick={() => openDialog("compare", v)}
                    >
                      <GitCompare size={13} aria-hidden="true" />
                      {tu("compareButton")}
                    </Button>
                  )}
                  {v.label === "PRODUCTION" && (
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="h-7 px-2 text-xs"
                      disabled={locked || anyBusy}
                      title={locked ? editLockedTooltip : tu("rollbackTooltip")}
                      onClick={() => openDialog("rollback", v)}
                    >
                      <Undo2 size={13} aria-hidden="true" />
                      {tu("rollbackButton")}
                    </Button>
                  )}
                  <AlertDialog>
                    <AlertDialogTrigger asChild>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        className="h-7 px-2 text-xs"
                        disabled={locked || anyBusy}
                        title={locked ? editLockedTooltip : t("restoreTooltip")}
                      >
                        {t("restoreButton")}
                      </Button>
                    </AlertDialogTrigger>
                    <AlertDialogContent>
                      <AlertDialogHeader>
                        <AlertDialogTitle>{t("restoreConfirmTitle", { version: v.versionNumber })}</AlertDialogTitle>
                        <AlertDialogDescription>{t("restoreConfirmBody")}</AlertDialogDescription>
                      </AlertDialogHeader>
                      <AlertDialogFooter>
                        <AlertDialogCancel>{t("cancelButton")}</AlertDialogCancel>
                        <AlertDialogAction onClick={() => void restore(v.id)}>{t("restoreButton")}</AlertDialogAction>
                      </AlertDialogFooter>
                    </AlertDialogContent>
                  </AlertDialog>
                </div>
              </div>

              {expandedId === v.id && (
                <div className="mt-2 border-t border-border pt-2">
                  {expandedConfig === "loading" && (
                    <div className="flex items-center gap-2 text-xs text-muted-foreground">
                      <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                      {t("loadingDetails")}
                    </div>
                  )}
                  {expandedConfig === "error" && (
                    <div className="text-xs text-muted-foreground">{t("detailsFailed")}</div>
                  )}
                  {expandedConfig && expandedConfig !== "loading" && expandedConfig !== "error" && (
                    <VersionConfigSummary config={expandedConfig} t={t} />
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {dialog && (
        <AgentVersionActionDialog
          agentId={agentId}
          mode={dialog.mode}
          fromNumber={dialog.from}
          toNumber={dialog.version.versionNumber}
          open
          onOpenChange={(o) => {
            if (!o) setDialog(null);
          }}
          busy={busyId === dialog.version.id}
          gate={gate}
          error={dialogError}
          locked={locked}
          onConfirm={(o) => void confirmDialog(o)}
        />
      )}
    </div>
  );
}

/** Compact read-out of one decoded AgentConfigSnapshot - the salient fields
 * an operator compares against the current agent, not a full JSON dump. */
function VersionConfigSummary({ config, t }: { config: any; t: (key: string, vars?: any) => string }) {
  const flags: string[] = [];
  if (config.knowledgeBaseEnabled) flags.push(t("flagKnowledge"));
  if (config.sarvamEnabled) flags.push(t("flagSarvam"));
  if (config.e2bEnabled) flags.push(t("flagSandbox"));
  if (config.codeWorkspaceEnabled) flags.push(t("flagCodeWorkspace"));
  if (config.costRoutingEnabled) flags.push(t("flagCostRouting"));
  if (config.xaiServerToolsEnabled) flags.push(t("flagXai"));
  if (config.biometricConsentEnabled) flags.push(t("flagBiometric"));
  if (config.knowledgeWriteEnabled) flags.push(t("flagKnowledgeWrite"));
  if (config.shareMemoryWithTenant) flags.push(t("flagSharedMemory"));

  const toolCount = Array.isArray(config.allowedToolNames) ? config.allowedToolNames.length : null;
  const guardrailCount = Array.isArray(config.enabledGuardrails) ? config.enabledGuardrails.length : 0;

  return (
    <dl className="grid grid-cols-1 gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
      <Row label={t("fieldModel")} value={`${config.providerName || "—"}${config.model ? ` / ${config.model}` : ""}`} />
      <Row
        label={t("fieldFallback")}
        value={
          config.fallbackProviderName
            ? `${config.fallbackProviderName}${config.fallbackModel ? ` / ${config.fallbackModel}` : ""}`
            : t("none")
        }
      />
      <Row
        label={t("fieldGeneration")}
        value={[
          config.temperature != null ? `temp ${config.temperature}` : null,
          config.topP != null ? `topP ${config.topP}` : null,
          config.maxTokens != null ? `${config.maxTokens} tok` : null,
        ]
          .filter(Boolean)
          .join(" · ")}
      />
      <Row
        label={t("fieldModes")}
        value={[config.tuningMode, config.reasoningDepth, config.experienceMode, config.autonomyTier]
          .filter(Boolean)
          .join(" · ")}
      />
      <Row
        label={t("fieldTools")}
        value={
          toolCount === null
            ? t("toolsUnrestricted")
            : toolCount === 0
              ? t("toolsNone")
              : t("toolsCount", { count: toolCount })
        }
      />
      <Row label={t("fieldGuardrails")} value={guardrailCount === 0 ? t("none") : String(guardrailCount)} />
      {flags.length > 0 && <Row label={t("fieldFlags")} value={flags.join(" · ")} />}
      {config.systemPrompt && (
        <div className="sm:col-span-2">
          <dt className="text-muted-foreground">{t("fieldPrompt")}</dt>
          <dd className="mt-0.5 whitespace-pre-wrap break-words rounded bg-muted/50 p-2 font-mono text-[11px] text-muted-foreground">
            {config.systemPrompt.length > 400 ? `${config.systemPrompt.slice(0, 400)}…` : config.systemPrompt}
          </dd>
        </div>
      )}
    </dl>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline gap-2">
      <dt className="shrink-0 text-muted-foreground">{label}</dt>
      <dd className="min-w-0 truncate text-foreground">{value || "—"}</dd>
    </div>
  );
}
