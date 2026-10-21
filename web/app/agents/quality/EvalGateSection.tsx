"use client";

import { useCallback, useEffect, useState } from "react";
import { FlaskConical, RefreshCw, ShieldCheck, ShieldX, Trash2 } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
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
} from "../../components/ui/alert-dialog";
import { Badge } from "../../components/ui/badge";
import { Button, buttonVariants } from "../../components/ui/button";
import { Card, CardContent } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import Select from "../../components/Select";
import { Section, SECTION_ACTION_GROUP_CLASS } from "../../components/ax/page-kit";
import { api } from "../../lib/api";

/**
 * Promote-gate card (EvalGateController, V260 + V343): the "CI for agents"
 * control. A configured+enabled gate blocks PRODUCTION promotion until a
 * PASSED eval run exists for the exact version config AND the dataset's
 * current content hash - a dataset edited after its last run reports
 * DATASET_STALE (the hash no longer matches) and requires a re-run.
 *
 * The card shows the gate config plus the live evaluation of the agent's
 * HEAD version ("would promoting what we run now pass?"). Literal English
 * strings, matching this route folder's documented i18n deferral (see
 * EvalDatasetsSection's header).
 */

interface GateConfig {
  id: string;
  agentId: string;
  datasetId: string;
  datasetName: string | null;
  enabled: boolean;
  maxRunAgeHours: number | null;
  requireSimulation: boolean;
  maxSimulationAgeHours: number | null;
  updatedBy: string | null;
  updatedAt: string;
}

interface GateEvaluation {
  configured: boolean;
  enabled: boolean;
  allowed: boolean;
  reason: string;
  message: string | null;
  qualifyingRunId: string | null;
  verdict: string | null;
  overallScore: number | null;
  runFinishedAt: string | null;
  simulationRequired: boolean;
  simulationSessionId: string | null;
  simulationVerdict: string | null;
  simulationFinishedAt: string | null;
}

interface GateStatus {
  gate: GateConfig | null;
  headVersionId: string | null;
  headEvaluation: GateEvaluation | null;
}

const REASON_LABEL: Record<string, string> = {
  PASSING_RUN: "Passing run on the current configuration",
  RUN_FAILED: "Latest run FAILED",
  RUN_INCONCLUSIVE: "Latest run measured nothing",
  RUN_STALE: "Passing run is older than the freshness window",
  DATASET_STALE: "Dataset edited since the last run - re-run required",
  NO_QUALIFYING_RUN: "No qualifying run for this configuration",
  VERSION_NOT_FOUND: "Not a stored version of this agent",
  NO_GATE: "No gate configured",
  GATE_DISABLED: "Gate disabled",
  SIM_NO_QUALIFYING_RUN: "No judged simulation has run this configuration",
  SIM_FAILED: "Latest simulation had a failed persona",
  SIM_INCONCLUSIVE: "Latest simulation measured nothing",
  SIM_STALE: "Passing simulation is older than its freshness window",
};

export default function EvalGateSection({ agentId }: { agentId: string | null }) {
  const [status, setStatus] = useState<GateStatus | null>(null);
  const [unavailable, setUnavailable] = useState(false); // older core without /gate (404)
  const [failed, setFailed] = useState(false);
  const [locked, setLocked] = useState(false); // FREE plan 403
  const [datasets, setDatasets] = useState<any[]>([]);

  const [showForm, setShowForm] = useState(false);
  const [datasetId, setDatasetId] = useState("");
  const [maxAge, setMaxAge] = useState("");
  const [requireSim, setRequireSim] = useState(false);
  const [maxSimAge, setMaxSimAge] = useState("");
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    if (!agentId) return;
    try {
      const data = await api.getEvalGate(agentId);
      setStatus(data);
      setFailed(false);
      setLocked(false);
    } catch (e: any) {
      if (e?.status === 404)
        setUnavailable(true); // route not on this core
      else if (e?.status === 403) setLocked(true);
      else setFailed(true);
    }
  }, [agentId]);

  useEffect(() => {
    setStatus(null);
    setUnavailable(false);
    setFailed(false);
    setLocked(false);
    void refresh();
  }, [agentId, refresh]);

  // Load datasets only when the configure form opens - not on every render.
  useEffect(() => {
    if (!showForm || !agentId || datasets.length > 0) return;
    api
      .listEvalDatasets(agentId)
      .then((d: any) => setDatasets(d || []))
      .catch(() => setDatasets([]));
  }, [showForm, agentId, datasets.length]);

  const save = useCallback(async () => {
    if (!agentId || !datasetId) return;
    setBusy(true);
    try {
      await api.upsertEvalGate(agentId, {
        datasetId,
        // Editing a gate keeps its enable state; "create" means enabled.
        enabled: status?.gate ? status.gate.enabled : true,
        maxRunAgeHours: maxAge.trim() ? parseInt(maxAge, 10) : null,
        requireSimulation: requireSim,
        maxSimulationAgeHours: maxSimAge.trim() ? parseInt(maxSimAge, 10) : null,
      });
      setShowForm(false);
      await refresh();
    } catch {
      /* shared toJson already raised the toast (400/403 included) */
    } finally {
      setBusy(false);
    }
  }, [agentId, datasetId, maxAge, requireSim, maxSimAge, status, refresh]);

  const toggleEnabled = useCallback(async () => {
    if (!agentId || !status?.gate) return;
    setBusy(true);
    try {
      await api.upsertEvalGate(agentId, {
        datasetId: status.gate.datasetId,
        enabled: !status.gate.enabled,
        maxRunAgeHours: status.gate.maxRunAgeHours,
        requireSimulation: status.gate.requireSimulation,
        maxSimulationAgeHours: status.gate.maxSimulationAgeHours,
      });
      await refresh();
    } catch {
      /* toast already shown */
    } finally {
      setBusy(false);
    }
  }, [agentId, status, refresh]);

  const remove = useCallback(async () => {
    if (!agentId) return;
    setBusy(true);
    try {
      await api.deleteEvalGate(agentId);
      await refresh();
    } catch {
      /* toast already shown */
    } finally {
      setBusy(false);
    }
  }, [agentId, refresh]);

  if (!agentId || unavailable) return null; // older core - nothing to show

  const evaluation = status?.headEvaluation || null;
  const evaluationBadge = !evaluation ? null : evaluation.allowed ? (
    <Badge variant="state" state="var(--state-success)">
      Promotion allowed
    </Badge>
  ) : (
    <Badge variant="destructive">Promotion blocked</Badge>
  );

  return (
    <Section
      title="Promotion gate"
      description="Require a passing eval run on the exact configuration before this agent can be labelled Production — the CI check for agent changes."
      actions={
        <div className={SECTION_ACTION_GROUP_CLASS}>
          <Button variant="outline" size="sm" onClick={refresh}>
            <RefreshCw size={14} aria-hidden="true" /> Refresh
          </Button>
          {!status?.gate ? (
            <Button size="sm" onClick={() => setShowForm((v) => !v)} disabled={locked}>
              Configure gate
            </Button>
          ) : (
            <>
              <Button
                variant="outline"
                size="sm"
                disabled={locked}
                onClick={() => {
                  setDatasetId(status.gate!.datasetId);
                  setMaxAge(status.gate!.maxRunAgeHours?.toString() ?? "");
                  setRequireSim(status.gate!.requireSimulation);
                  setMaxSimAge(status.gate!.maxSimulationAgeHours?.toString() ?? "");
                  setShowForm((v) => !v);
                }}
              >
                Edit
              </Button>
              <Button variant="outline" size="sm" onClick={toggleEnabled} disabled={busy}>
                {status.gate.enabled ? "Disable" : "Enable"}
              </Button>
              <AlertDialog>
                <AlertDialogTrigger asChild>
                  <Button variant="outline" size="sm" disabled={busy}>
                    <Trash2 size={14} aria-hidden="true" /> Remove
                  </Button>
                </AlertDialogTrigger>
                <AlertDialogContent>
                  <AlertDialogHeader>
                    <AlertDialogTitle>Remove the promotion gate?</AlertDialogTitle>
                    <AlertDialogDescription>
                      This removes the promotion gate configuration for this agent. Production promotion will no longer
                      require a passing eval run. This cannot be undone.
                    </AlertDialogDescription>
                  </AlertDialogHeader>
                  <AlertDialogFooter>
                    <AlertDialogCancel>Cancel</AlertDialogCancel>
                    <AlertDialogAction
                      className={buttonVariants({ variant: "destructive" })}
                      onClick={remove}
                      disabled={busy}
                    >
                      Remove
                    </AlertDialogAction>
                  </AlertDialogFooter>
                </AlertDialogContent>
              </AlertDialog>
            </>
          )}
        </div>
      }
    >
      {locked && (
        <Alert>
          <AlertTitle>Premium feature</AlertTitle>
          <AlertDescription>
            Configuring a promotion gate requires a Premium plan (it consumes premium eval runs). Reading or removing an
            existing gate works on any plan.
          </AlertDescription>
        </Alert>
      )}
      {failed && (
        <Alert variant="destructive">
          <AlertTitle>Couldn&apos;t load the gate status</AlertTitle>
          <AlertDescription className="flex items-center gap-3">
            This is a load failure, not a missing gate.
            <Button variant="outline" size="sm" onClick={refresh}>
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {status?.gate ? (
        <Card>
          <CardContent className="space-y-3 pt-4">
            <div className="flex flex-wrap items-center gap-2">
              {status.gate.enabled ? (
                <Badge variant="accent">
                  <ShieldCheck size={12} className="mr-1" aria-hidden="true" /> Enabled
                </Badge>
              ) : (
                <Badge variant="secondary">
                  <ShieldX size={12} className="mr-1" aria-hidden="true" /> Disabled
                </Badge>
              )}
              {evaluationBadge}
              <span className="text-sm text-foreground">
                Dataset: <span className="font-medium">{status.gate.datasetName || status.gate.datasetId}</span>
              </span>
              {status.gate.maxRunAgeHours != null && (
                <span className="text-xs text-muted-foreground">runs expire after {status.gate.maxRunAgeHours}h</span>
              )}
              {status.gate.requireSimulation && (
                <span className="flex items-center gap-1 text-xs text-muted-foreground">
                  <FlaskConical size={11} aria-hidden="true" /> sim staging required
                  {status.gate.maxSimulationAgeHours != null && ` (within ${status.gate.maxSimulationAgeHours}h)`}
                  {evaluation?.simulationVerdict != null && ` · latest sim: ${evaluation.simulationVerdict}`}
                </span>
              )}
            </div>
            {evaluation && !evaluation.allowed && (
              <div className="text-sm text-muted-foreground">
                <span className="font-medium text-foreground">
                  {REASON_LABEL[evaluation.reason] || evaluation.reason}:
                </span>{" "}
                {evaluation.message}
              </div>
            )}
            {evaluation?.allowed && (
              <div className="text-sm text-muted-foreground">
                Qualifying run{evaluation.qualifyingRunId ? ` ${evaluation.qualifyingRunId.slice(0, 8)}…` : ""}
                {evaluation.overallScore != null && ` scored ${Math.round(evaluation.overallScore * 100)}%`}.
              </div>
            )}
          </CardContent>
        </Card>
      ) : (
        !failed &&
        !locked &&
        status != null && (
          <p className="text-sm text-muted-foreground">
            No gate configured — promoting a version to Production is currently unrestricted. Configure one to require a
            passing dataset run first.
          </p>
        )
      )}

      {showForm && (
        <Card>
          <CardContent className="space-y-3 pt-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-gate-dataset">
                  Gated dataset
                </label>
                <Select
                  id="eval-gate-dataset"
                  value={datasetId}
                  onChange={(e: any) => setDatasetId(e.target.value)}
                  options={[
                    { value: "", label: "Select a dataset" },
                    ...datasets.map((d: any) => ({ value: d.id, label: d.name })),
                  ]}
                />
              </div>
              <div className="space-y-1">
                <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-gate-max-age">
                  Freshness window (hours, optional)
                </label>
                <Input
                  id="eval-gate-max-age"
                  value={maxAge}
                  onChange={(e) => setMaxAge(e.target.value)}
                  placeholder="e.g. 72 — blank = never expires"
                />
              </div>
            </div>
            <p className="text-xs text-muted-foreground">
              The gate also tracks the dataset&apos;s content hash: editing the dataset after a run makes that run stale
              and blocks promotion until a fresh run passes.
            </p>
            <div className="space-y-2 rounded-md border border-border p-3">
              <label className="flex items-start gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={requireSim}
                  onChange={(e) => setRequireSim(e.target.checked)}
                  className="mt-0.5"
                />
                <span>
                  <span className="font-medium">Require simulation staging</span>
                  <span className="block text-xs text-muted-foreground">
                    Promote also needs the latest judged simulation session of this exact configuration to have every
                    persona pass — the digital-twin dry run before production.
                  </span>
                </span>
              </label>
              {requireSim && (
                <div className="space-y-1 pl-6">
                  <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-gate-sim-age">
                    Simulation freshness window (hours, optional)
                  </label>
                  <Input
                    id="eval-gate-sim-age"
                    value={maxSimAge}
                    onChange={(e) => setMaxSimAge(e.target.value)}
                    placeholder="e.g. 168 — blank = never expires"
                  />
                  <p className="text-xs text-muted-foreground">
                    Start a sim under Simulations; stale sim evidence blocks promote the same way stale eval evidence
                    does.
                  </p>
                </div>
              )}
            </div>
            <div className="flex justify-end gap-2">
              <Button variant="outline" size="sm" onClick={() => setShowForm(false)}>
                Cancel
              </Button>
              <Button size="sm" onClick={save} disabled={busy || !datasetId}>
                Save gate
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </Section>
  );
}
