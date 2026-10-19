"use client";

import { useCallback, useEffect, useState } from "react";
import { AlertTriangle, Plus, Trash2, Trophy, Info } from "lucide-react";
import { api, type ProviderModelInfo, type RlPolicyArm } from "../../lib/api";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import Select from "../Select";
import ModelInfoCard, { modelOptionLabel } from "./ModelInfoCard";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import {
  formatObservedReward,
  LOW_CONFIDENCE_PULLS,
  modelFromArmConfig,
  modelRouteArmBody,
  observedRewardRate,
} from "../../lib/rl-arms";

const CUSTOM_MODEL = "__custom__";

/**
 * Arms editor for the MODEL_ROUTE bandit policy.
 *
 * The bug this exists to fix: a policy's ARMS are the set of options it picks
 * between, and a bandit with zero arms cannot select anything. In production
 * MODEL_ROUTE was switched ON for a tenant and had no arms at all, so 97
 * episodes ran, 0 were attributed to an arm, 0 pulls were recorded, and the
 * thumbs up/down people left trained precisely nothing - while the Learning
 * screen showed a policy toggle in the "on" position. There was no way to add
 * an arm from the console, and no message anywhere saying an armless policy is
 * inert. Both halves are fixed here: the zero-arm state is called out in the
 * exact words of the failure, and arms can be added and removed.
 *
 * Read-only for non-admins (they still see the diagnosis - it explains why
 * their agent isn't learning even though they can't be the one to fix it).
 */
export default function RlArmsEditor({
  agent,
  policy,
  canEdit,
  onChanged,
}: {
  agent: any;
  /** The MODEL_ROUTE AgentPolicy row, or null if the backend has none yet. */
  policy: any;
  canEdit: boolean;
  onChanged?: () => void;
}) {
  const dimension = "MODEL_ROUTE";
  const agentId = agent?.id;
  // This component is deliberately plain-English (see the header comment), but
  // the model-catalog strings (deprecation affix, the info card's badges) are
  // shared with AgentWorkbench's pickers, so they reuse that namespace rather
  // than duplicating the copy.
  const tModels = useTranslations("agentWorkbench");
  // null = couldn't read the endpoint at all (backend older than this UI).
  // [] = read fine, genuinely zero arms. The distinction is the whole point.
  const [arms, setArms] = useState<RlPolicyArm[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<RlPolicyArm | null>(null);

  const [modelChoice, setModelChoice] = useState("");
  const [customModel, setCustomModel] = useState("");
  const [contextBucket, setContextBucket] = useState("");
  const [catalog, setCatalog] = useState<ProviderModelInfo[]>([]);

  const load = useCallback(async () => {
    if (!agentId) return;
    setLoading(true);
    try {
      setArms(await api.listRlArms(agentId, dimension));
    } catch {
      setArms(null);
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => {
    void load();
  }, [load]);

  // Model catalog for the agent's own provider. A MODEL_ROUTE arm names a
  // model, not a provider (AgentChatService keeps the agent's provider and
  // swaps only the model), so this is the right list to offer. A provider with
  // no published catalog isn't a dead end - the free-text field below covers it.
  useEffect(() => {
    if (!adding || !agent?.providerName) return undefined;
    let cancelled = false;
    api
      .listProviderModels(agent.providerName)
      .then((models: any) => {
        if (!cancelled) setCatalog(models || []);
      })
      .catch(() => {
        if (!cancelled) setCatalog([]);
      });
    return () => {
      cancelled = true;
    };
  }, [adding, agent?.providerName]);

  function resetForm() {
    setModelChoice("");
    setCustomModel("");
    setContextBucket("");
    setError(null);
  }

  const chosenModel = (modelChoice === CUSTOM_MODEL ? customModel : modelChoice).trim();

  async function handleAdd() {
    if (!chosenModel) return;
    setBusy(true);
    setError(null);
    try {
      const body = modelRouteArmBody(chosenModel);
      await api.createRlArm(agentId, dimension, {
        ...body,
        contextBucket: contextBucket.trim() || null,
      });
      setAdding(false);
      resetForm();
      await load();
      onChanged?.();
    } catch (err: any) {
      // The backend rejects an arm whose provider/model this tenant can't
      // actually use (a BYOK tenant with no credential for it, a model missing
      // from the catalog) and says why in the response body. api.ts turns that
      // into the Error's message - pin it verbatim next to the form so it
      // survives the toast disappearing, and never replace it with a generic
      // "couldn't add arm", which would hide the one fact that helps.
      setError(err?.message || "Couldn't add that arm.");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(arm: RlPolicyArm) {
    setBusy(true);
    setError(null);
    try {
      await api.deleteRlArm(agentId, dimension, arm.id);
      setPendingDelete(null);
      await load();
      onChanged?.();
    } catch (err: any) {
      setPendingDelete(null);
      setError(err?.message || "Couldn't remove that arm.");
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <p className="text-sm text-muted-foreground">Loading model routing arms…</p>;
  }

  // Endpoint unreachable/absent - say that, rather than implying zero arms.
  if (arms === null) {
    return (
      <Alert>
        <Info aria-hidden="true" />
        <AlertTitle>Arm configuration isn&apos;t available</AlertTitle>
        <AlertDescription>
          <p>
            This deployment&apos;s API didn&apos;t return the arm list for model routing, so it can&apos;t be shown or
            edited here yet.
          </p>
        </AlertDescription>
      </Alert>
    );
  }

  const policyEnabled = !!policy?.enabled;
  const empty = arms.length === 0;

  return (
    <div className="space-y-4">
      {/* The exact silent failure, named. */}
      {empty && policyEnabled && (
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>Model routing is on, but it has nothing to choose between</AlertTitle>
          <AlertDescription>
            <p>
              This policy is switched on and has <strong>zero arms</strong>. An arm is one candidate model the policy
              can try; with none configured there is nothing for it to select, so every turn runs on the agent&apos;s
              normal model and no feedback is attributed to anything.{" "}
              <strong>This agent cannot learn model routing until at least one arm is added</strong> — any thumbs up or
              down collected in the meantime trains nothing.
            </p>
            {!canEdit && <p>Ask a tenant admin to add candidate models here.</p>}
          </AlertDescription>
        </Alert>
      )}

      {empty && !policyEnabled && (
        <Alert>
          <Info aria-hidden="true" />
          <AlertTitle>No candidate models configured</AlertTitle>
          <AlertDescription>
            <p>
              Add at least one arm before switching model routing on — an enabled policy with no arms has nothing to
              select and learns nothing.
            </p>
          </AlertDescription>
        </Alert>
      )}

      {error && (
        <Alert variant="destructive">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>That arm wasn&apos;t saved</AlertTitle>
          <AlertDescription>
            <p>{error}</p>
          </AlertDescription>
        </Alert>
      )}

      {!empty && (
        <Card className="gap-0 p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Candidate model</TableHead>
                <TableHead>Context</TableHead>
                <TableHead className="text-right">Turns tried</TableHead>
                <TableHead className="text-right">Observed reward</TableHead>
                {canEdit && <TableHead className="w-10" />}
              </TableRow>
            </TableHeader>
            <TableBody>
              {arms.map((arm) => {
                const observed = observedRewardRate(arm);
                const model = modelFromArmConfig(arm.armConfigJson);
                return (
                  <TableRow key={arm.id}>
                    <TableCell>
                      <div className="font-medium text-foreground">{arm.armKey}</div>
                      {model && model !== arm.armKey && (
                        <div className="font-mono text-xs text-muted-foreground">{model}</div>
                      )}
                      {!model && <div className="font-mono text-xs text-muted-foreground">{arm.armConfigJson}</div>}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">
                      {arm.contextBucket ? (
                        <span className="font-mono">{arm.contextBucket}</span>
                      ) : (
                        <span>All traffic</span>
                      )}
                    </TableCell>
                    <TableCell className="text-right tabular-nums text-muted-foreground">
                      {observed.pulls.toLocaleString()}
                    </TableCell>
                    <TableCell className="text-right">
                      {observed.rate === null ? (
                        // Never pulled. The stored posterior would average to a
                        // confident-looking 50% here (Beta(1,1) prior); showing
                        // that would be inventing a result. Say "not tried".
                        <Badge variant="outline">Not tried yet</Badge>
                      ) : (
                        <span className="inline-flex items-center justify-end gap-2">
                          {!observed.lowConfidence && <Trophy size={12} className="text-primary" aria-hidden="true" />}
                          <span
                            className={cn(
                              "font-semibold tabular-nums",
                              observed.lowConfidence ? "text-muted-foreground" : "text-foreground"
                            )}
                          >
                            {formatObservedReward(observed)}
                          </span>
                        </span>
                      )}
                      {observed.rate !== null && observed.lowConfidence && (
                        <div className="text-xs text-muted-foreground">too few turns to trust</div>
                      )}
                    </TableCell>
                    {canEdit && (
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="text-muted-foreground hover:text-destructive"
                          aria-label={`Remove arm ${arm.armKey}`}
                          disabled={busy}
                          onClick={() => setPendingDelete(arm)}
                        >
                          <Trash2 size={15} aria-hidden="true" />
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          <p className="border-t border-border px-4 py-3 text-xs leading-relaxed text-muted-foreground">
            <strong>Observed reward</strong> is the average feedback score this model actually earned across the turns
            it was tried on — it is measured, not predicted, and an arm that has never been tried shows no figure at all
            rather than a placeholder. Anything under {LOW_CONFIDENCE_PULLS} turns is flagged, because a handful of
            lucky turns can look like a winner.
          </p>
        </Card>
      )}

      {canEdit && !adding && (
        <Button
          size="sm"
          variant={empty ? "default" : "outline"}
          onClick={() => {
            resetForm();
            setAdding(true);
          }}
        >
          <Plus size={14} aria-hidden="true" /> Add a candidate model
        </Button>
      )}

      {canEdit && adding && (
        <Card className="gap-4 p-5">
          <div>
            <div className="text-sm font-semibold text-foreground">Add a candidate model</div>
            <p className="mt-0.5 text-xs text-muted-foreground">
              The policy will send a share of this agent&apos;s turns to this model and keep the ones that score best.
              It stays on {agent?.providerName || "the agent's provider"} — an arm changes the model, not the provider.
            </p>
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-foreground" htmlFor="rl-arm-model">
              Model
            </label>
            <Select
              id="rl-arm-model"
              searchable
              placeholder="Choose a model"
              value={modelChoice}
              onChange={(e: any) => setModelChoice(e.target.value)}
              options={[
                ...catalog.map((m) => ({
                  value: m.modelId,
                  label: modelOptionLabel(m, tModels("modelDeprecatedLabel")),
                })),
                { value: CUSTOM_MODEL, label: "Other (enter manually)" },
              ]}
            />
            {(modelChoice === CUSTOM_MODEL || catalog.length === 0) && (
              <Input
                aria-label="Model id"
                placeholder="e.g. gpt-4o-mini"
                value={customModel}
                onChange={(e) => {
                  setCustomModel(e.target.value);
                  setModelChoice(CUSTOM_MODEL);
                }}
              />
            )}
            {/* Catalog metadata for the chosen candidate - nothing on the
             * manual-entry path, where there's no catalog row to describe. */}
            {modelChoice !== CUSTOM_MODEL && (
              <ModelInfoCard className="mt-1.5" model={catalog.find((m) => m.modelId === modelChoice)} />
            )}
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-medium text-foreground" htmlFor="rl-arm-bucket">
              Context bucket <span className="font-normal text-muted-foreground">(optional)</span>
            </label>
            <Input
              id="rl-arm-bucket"
              placeholder="Leave blank to apply to all traffic"
              value={contextBucket}
              onChange={(e) => setContextBucket(e.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              Scopes what this arm learns to one slice of traffic. Leave it blank unless you are deliberately keeping
              separate scores per slice.
            </p>
          </div>

          <div className="flex flex-wrap gap-2">
            <Button size="sm" disabled={busy || !chosenModel} onClick={handleAdd}>
              {busy ? "Adding…" : "Add arm"}
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={busy}
              onClick={() => {
                setAdding(false);
                resetForm();
              }}
            >
              Cancel
            </Button>
          </div>
        </Card>
      )}

      <AlertDialog open={!!pendingDelete} onOpenChange={(open: boolean) => !open && setPendingDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {(pendingDelete?.pulls || 0) > 0 ? "Discard this arm's learned history?" : "Remove this arm?"}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {(pendingDelete?.pulls || 0) > 0 ? (
                <>
                  <strong>{pendingDelete?.armKey}</strong> has been tried on{" "}
                  {(pendingDelete?.pulls || 0).toLocaleString()} turns. Removing it deletes the score those turns earned
                  — that history is not recoverable, and re-adding the same model starts it over from nothing.
                </>
              ) : (
                <>
                  <strong>{pendingDelete?.armKey}</strong> has never been tried, so nothing learned is lost. The policy
                  will stop considering it.
                </>
              )}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>Keep it</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={busy}
              onClick={(e: any) => {
                e.preventDefault();
                if (pendingDelete) void handleDelete(pendingDelete);
              }}
            >
              {busy ? "Removing…" : "Remove arm"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
