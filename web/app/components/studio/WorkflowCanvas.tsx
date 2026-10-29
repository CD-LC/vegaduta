"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import {
  Plus,
  Trash2,
  Play,
  Square,
  Save,
  Loader2,
  ArrowRight,
  CheckCircle2,
  XCircle,
  SkipForward,
  Clock,
  Workflow as WorkflowIcon,
  Layers,
} from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import {
  runWorkflow,
  studio,
  type StudioAgent,
  type StudioWorkflow,
  type WorkflowDefinition,
  type WorkflowStep,
  type WorkflowStepType,
} from "./studioClient";
import { chainFromAgents, newStepId, type StarterChain } from "./studioContent";
import { EMPTY_RUN, applyRunEvent, type RunState, type StepState } from "./workflowRun";
import WorkflowRunPanel from "./WorkflowRunPanel";
import { AddMenu, StepIcon } from "./workflowStepParts";

// The workflow designer + runner. Steps are the production designer's own
// vocabulary (trigger / agent / condition / approval / output); a run
// executes the visitor's real agents in order, streams every step, and the
// approval step genuinely parks the run until the buttons below are pressed.
// Run state + event reducer: ./workflowRun; the approval/trace panel:
// ./WorkflowRunPanel; the step icon and add menu: ./workflowStepParts.

type Props = {
  token: string;
  agents: StudioAgent[];
  workflows: StudioWorkflow[];
  readOnly: boolean;
  outOfBudget: boolean;
  workflowsMax: number;
  onBudget: (used: number, limit: number) => void;
  onWorkflowsChanged: (workflows: StudioWorkflow[]) => void;
  onFirstRun?: () => void;
};

function blankDefinition(agents: StudioAgent[]): WorkflowDefinition {
  return {
    steps: [
      { id: "t", type: "trigger", label: "Input arrives", config: { inputLabel: "Input" } },
      {
        id: newStepId(),
        type: "agent",
        label: "Do the job",
        config: { agentId: agents.find((a) => a.mine)?.id ?? agents[0]?.id ?? "", instruction: "{{input}}" },
      },
      { id: newStepId(), type: "output", label: "Result", config: {} },
    ],
  };
}

export default function WorkflowCanvas({
  token,
  agents,
  workflows,
  readOnly,
  outOfBudget,
  workflowsMax,
  onBudget,
  onWorkflowsChanged,
  onFirstRun,
}: Props) {
  const [selectedId, setSelectedId] = useState<string | null>(workflows.find((w) => w.mine)?.id ?? null);
  const [name, setName] = useState("");
  const [def, setDef] = useState<WorkflowDefinition>({ steps: [] });
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [run, setRun] = useState<RunState>(EMPTY_RUN);
  const [deciding, setDeciding] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const firstRunRef = useRef(false);

  const selected = useMemo(() => workflows.find((w) => w.id === selectedId) ?? null, [workflows, selectedId]);

  useEffect(() => {
    if (selected) {
      setName(selected.name);
      setDef(selected.definition);
      setDirty(false);
      setRun(EMPTY_RUN);
    }
  }, [selected]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const startNew = (chain?: StarterChain | null) => {
    setSelectedId(null);
    setName(chain ? chain.name : "My workflow");
    setDef(chain ? { steps: chain.steps } : blankDefinition(agents));
    setInput("");
    setDirty(true);
    setRun(EMPTY_RUN);
    setSaveError(null);
  };

  // First visit with no workflow of their own: open on THEIR use case (their agents, chained, with a human sign-off)
  // instead of an empty canvas or someone else's example.
  const autoStartedRef = useRef(false);
  useEffect(() => {
    if (autoStartedRef.current || readOnly || selectedId || dirty) return;
    if (workflows.some((w) => w.mine)) return;
    const chain = chainFromAgents(agents);
    if (!chain) return;
    autoStartedRef.current = true;
    startNew(chain);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agents, workflows, readOnly, selectedId, dirty]);

  const updateStep = (id: string, patch: Partial<WorkflowStep>) => {
    setDef({
      steps: def.steps.map((s) =>
        s.id === id ? { ...s, ...patch, config: { ...s.config, ...(patch.config ?? {}) } } : s
      ),
    });
    setDirty(true);
  };
  const removeStep = (id: string) => {
    setDef({ steps: def.steps.filter((s) => s.id !== id) });
    setDirty(true);
  };
  const addStep = (type: WorkflowStepType, afterIndex: number) => {
    const base: WorkflowStep =
      type === "agent"
        ? {
            id: newStepId(),
            type,
            label: "Agent step",
            config: { agentId: agents[0]?.id ?? "", instruction: "{{previous}}" },
          }
        : type === "condition"
          ? { id: newStepId(), type, label: "Only continue if…", config: { contains: "", onFalse: "stop" } }
          : type === "approval"
            ? { id: newStepId(), type, label: "You approve", config: { message: "Continue?" } }
            : { id: newStepId(), type, label: "Result", config: {} };
    const steps = [...def.steps];
    steps.splice(afterIndex + 1, 0, base);
    setDef({ steps });
    setDirty(true);
  };

  const save = async (): Promise<StudioWorkflow | null> => {
    setSaving(true);
    setSaveError(null);
    const res = selected
      ? await studio.updateWorkflow(token, selected.id, name.trim() || "My workflow", def)
      : await studio.createWorkflow(token, name.trim() || "My workflow", def);
    setSaving(false);
    if (!res.ok) {
      setSaveError(res.error.message);
      return null;
    }
    const next = selected ? workflows.map((w) => (w.id === res.value.id ? res.value : w)) : [...workflows, res.value];
    onWorkflowsChanged(next);
    setSelectedId(res.value.id);
    setDirty(false);
    return res.value;
  };

  const remove = async () => {
    if (!selected) return;
    const res = await studio.deleteWorkflow(token, selected.id);
    if (res.ok) {
      onWorkflowsChanged(workflows.filter((w) => w.id !== selected.id));
      setSelectedId(null);
      setDef({ steps: [] });
      setName("");
    } else {
      setSaveError(res.error.message);
    }
  };

  const execute = async () => {
    if (run.running) return;
    let target = selected;
    if (!target || dirty) {
      target = await save();
      if (!target) return;
    }
    if (!firstRunRef.current) {
      firstRunRef.current = true;
      onFirstRun?.();
    }
    const steps: Record<string, StepState> = {};
    for (const s of def.steps) steps[s.id] = { status: "idle", events: [] };
    const state: RunState = { ...EMPTY_RUN, running: true, steps };
    setRun({ ...state, steps: { ...steps } });
    const controller = new AbortController();
    abortRef.current = controller;
    const push = () => setRun({ ...state, steps: { ...state.steps } });
    await runWorkflow(
      token,
      target.id,
      input,
      (event) => {
        applyRunEvent(state, event, onBudget);
        push();
      },
      controller.signal
    );
    state.running = false;
    state.approval = null;
    push();
  };

  const decide = async (approved: boolean) => {
    if (!run.runId || deciding) return;
    setDeciding(true);
    await studio.decide(token, run.runId, approved);
    setDeciding(false);
  };

  const stop = () => abortRef.current?.abort();

  const agentName = (id: unknown) => agents.find((a) => a.id === id)?.name ?? "(pick an agent)";
  const canRun = def.steps.length > 0 && !readOnly && !outOfBudget && !run.running && !saving;

  return (
    <div className="space-y-5">
      {/* Picker: saved workflows + templates */}
      <div className="flex flex-wrap items-center gap-2">
        {workflows.map((w) => (
          <button
            key={w.id}
            type="button"
            onClick={() => setSelectedId(w.id)}
            className={`inline-flex items-center gap-2 rounded-full border px-3 py-1.5 text-sm transition-colors ${
              selectedId === w.id
                ? "border-amber-500 bg-amber-500/10 ring-1 ring-amber-500/30"
                : "border-border/60 hover:border-amber-400/60"
            }`}
          >
            <WorkflowIcon aria-hidden="true" className="h-3.5 w-3.5 shrink-0" /> {w.name}
          </button>
        ))}
        {!readOnly && workflows.length < workflowsMax && chainFromAgents(agents) && (
          <Button variant="outline" size="sm" onClick={() => startNew(chainFromAgents(agents))}>
            <Layers aria-hidden="true" className="me-1 h-3.5 w-3.5 shrink-0" /> Chain my agents
          </Button>
        )}
        {!readOnly && workflows.length < workflowsMax && (
          <Button variant="outline" size="sm" onClick={() => startNew()}>
            <Plus aria-hidden="true" className="me-1 h-3.5 w-3.5 shrink-0" /> Blank
          </Button>
        )}
        {workflows.length >= workflowsMax && (
          <span className="text-xs text-muted-foreground">
            This sandbox holds {workflowsMax} workflows. Delete one to design another.
          </span>
        )}
      </div>

      {def.steps.length === 0 && (
        <p className="rounded-xl border border-dashed border-border/60 p-4 text-sm text-muted-foreground">
          {chainFromAgents(agents)
            ? "No workflow yet. Chain your agents to start from your own use case, or begin blank."
            : "Build an agent first - a workflow chains the agents you have designed."}
        </p>
      )}

      {def.steps.length > 0 && (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <Input
              value={name}
              disabled={readOnly}
              maxLength={120}
              className="max-w-xs"
              onChange={(e) => {
                setName(e.target.value);
                setDirty(true);
              }}
            />
            <Button variant="outline" size="sm" onClick={save} disabled={!dirty || saving || readOnly}>
              {saving ? (
                <Loader2 aria-hidden="true" className="me-1 h-3.5 w-3.5 animate-spin shrink-0" />
              ) : (
                <Save aria-hidden="true" className="me-1 h-3.5 w-3.5 shrink-0" />
              )}{" "}
              Save
            </Button>
            {selected && !readOnly && (
              <Button variant="ghost" size="sm" onClick={remove} disabled={run.running}>
                <Trash2 aria-hidden="true" className="me-1 h-3.5 w-3.5 shrink-0" /> Delete
              </Button>
            )}
            {saveError && <span className="text-sm text-destructive">{saveError}</span>}
          </div>

          {/* Canvas: a horizontal chain of step cards with add buttons between them */}
          <div className="overflow-x-auto pb-2">
            <div className="flex min-w-max items-stretch gap-2">
              {def.steps.map((step, i) => {
                const st = run.steps[step.id];
                return (
                  <div key={step.id} className="flex items-stretch gap-2">
                    <StepCard
                      step={step}
                      state={st}
                      agents={agents}
                      agentName={agentName}
                      readOnly={readOnly || run.running}
                      onChange={(patch) => updateStep(step.id, patch)}
                      onRemove={step.type === "trigger" ? undefined : () => removeStep(step.id)}
                    />
                    {i < def.steps.length - 1 || step.type !== "output" ? (
                      <div className="flex flex-col items-center justify-center">
                        <ArrowRight aria-hidden="true" className="h-4 w-4 text-muted-foreground shrink-0" />
                        {!readOnly && !run.running && def.steps.length < 8 && <AddMenu onAdd={(t) => addStep(t, i)} />}
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
          </div>

          {/* Run */}
          <div className="rounded-xl border border-border/60 p-4">
            <div className="mb-2 text-sm font-medium">
              {def.steps[0]?.config?.inputLabel ? String(def.steps[0].config.inputLabel) : "Input"}
            </div>
            <Textarea
              value={input}
              rows={2}
              maxLength={2000}
              disabled={readOnly || run.running}
              placeholder="The job, in your own words."
              onChange={(e) => setInput(e.target.value)}
            />
            <div className="mt-2 flex flex-wrap items-center gap-2">
              {!run.running ? (
                <Button onClick={execute} disabled={!canRun}>
                  <Play aria-hidden="true" className="me-2 h-4 w-4 shrink-0" /> Run workflow
                </Button>
              ) : (
                <Button variant="outline" onClick={stop}>
                  <Square aria-hidden="true" className="me-2 h-4 w-4 shrink-0" /> Stop watching
                </Button>
              )}
              {run.status && (
                <span className="inline-flex items-center gap-1.5 text-sm">
                  {run.status === "COMPLETED" ? (
                    <CheckCircle2 aria-hidden="true" className="h-4 w-4 text-emerald-600 shrink-0" />
                  ) : (
                    <XCircle aria-hidden="true" className="h-4 w-4 text-muted-foreground shrink-0" />
                  )}
                  {run.status.toLowerCase()}
                  {run.ms != null ? ` · ${(run.ms / 1000).toFixed(1)} s` : ""}
                </span>
              )}
              {outOfBudget && <span className="text-sm text-muted-foreground">Message allowance used up.</span>}
              {run.error && <span className="text-sm text-destructive">{run.error}</span>}
            </div>
          </div>

          <WorkflowRunPanel run={run} def={def} deciding={deciding} agentName={agentName} decide={decide} />
        </>
      )}
    </div>
  );
}

function StepCard({
  step,
  state,
  agents,
  agentName,
  readOnly,
  onChange,
  onRemove,
}: {
  step: WorkflowStep;
  state?: StepState;
  agents: StudioAgent[];
  agentName: (id: unknown) => string;
  readOnly: boolean;
  onChange: (patch: Partial<WorkflowStep>) => void;
  onRemove?: () => void;
}) {
  const status = state?.status ?? "idle";
  const ring =
    status === "running" || status === "waiting"
      ? "border-primary ring-2 ring-primary/30"
      : status === "ok"
        ? "border-emerald-500/60"
        : status === "skipped"
          ? "border-border/40 opacity-70"
          : status === "rejected" || status === "stopped" || status === "error"
            ? "border-destructive/60"
            : "border-border/60";
  return (
    <div className={`w-64 rounded-xl border bg-background p-3 text-sm transition-colors ${ring}`}>
      <div className="mb-2 flex items-center gap-2">
        <StepIcon type={step.type} />
        <input
          value={step.label}
          disabled={readOnly}
          maxLength={80}
          onChange={(e) => onChange({ label: e.target.value })}
          className="min-w-0 flex-1 bg-transparent font-medium outline-none"
        />
        {status === "running" && (
          <Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin text-primary shrink-0" />
        )}
        {status === "waiting" && <Clock aria-hidden="true" className="h-3.5 w-3.5 text-amber-600 shrink-0" />}
        {status === "ok" && <CheckCircle2 aria-hidden="true" className="h-3.5 w-3.5 text-emerald-600 shrink-0" />}
        {status === "skipped" && (
          <SkipForward aria-hidden="true" className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
        )}
        {(status === "rejected" || status === "stopped" || status === "error") && (
          <XCircle aria-hidden="true" className="h-3.5 w-3.5 text-destructive shrink-0" />
        )}
        {onRemove && !readOnly && (
          <button
            type="button"
            onClick={onRemove}
            className="text-muted-foreground hover:text-destructive"
            aria-label="Remove step"
          >
            <Trash2 aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
          </button>
        )}
      </div>
      <Badge variant="outline" className="mb-2 text-[10px] uppercase">
        {step.type}
      </Badge>

      {step.type === "trigger" && (
        <p className="text-xs text-muted-foreground">
          Starts with what you type below. On a real account: a WhatsApp message, a form, a webhook, a schedule.
        </p>
      )}
      {step.type === "agent" && (
        <div className="space-y-2">
          <select
            value={String(step.config.agentId ?? "")}
            disabled={readOnly}
            onChange={(e) => onChange({ config: { agentId: e.target.value } })}
            className="w-full rounded-lg border border-border/60 bg-background px-2 py-1.5 text-xs"
          >
            <option value="">Pick an agent…</option>
            {agents
              .filter((a) => a.mine || a.id === step.config.agentId)
              .map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
          </select>
          <textarea
            value={String(step.config.instruction ?? "")}
            disabled={readOnly}
            rows={4}
            maxLength={1500}
            onChange={(e) => onChange({ config: { instruction: e.target.value } })}
            className="w-full rounded-lg border border-border/60 bg-background p-2 font-mono text-[11px]"
            placeholder="Instruction. Use {{input}} and {{previous}}."
          />
          <p className="text-[10px] text-muted-foreground">
            {agentName(step.config.agentId)} · {"{{input}}"} = trigger text, {"{{previous}}"} = last step&apos;s output
          </p>
        </div>
      )}
      {step.type === "condition" && (
        <div className="space-y-2">
          <label className="block text-xs">
            Continue only if the previous output contains
            <input
              value={String(step.config.contains ?? "")}
              disabled={readOnly}
              maxLength={80}
              onChange={(e) => onChange({ config: { contains: e.target.value } })}
              className="mt-1 w-full rounded-lg border border-border/60 bg-background px-2 py-1 text-xs"
              placeholder="e.g. urgency: high"
            />
          </label>
          <label className="block text-xs">
            Otherwise
            <select
              value={String(step.config.onFalse ?? "stop")}
              disabled={readOnly}
              onChange={(e) => onChange({ config: { onFalse: e.target.value } })}
              className="mt-1 w-full rounded-lg border border-border/60 bg-background px-2 py-1 text-xs"
            >
              <option value="stop">stop the workflow</option>
              <option value="skip">skip the next step</option>
            </select>
          </label>
        </div>
      )}
      {step.type === "approval" && (
        <textarea
          value={String(step.config.message ?? "")}
          disabled={readOnly}
          rows={2}
          maxLength={200}
          onChange={(e) => onChange({ config: { message: e.target.value } })}
          className="w-full rounded-lg border border-border/60 bg-background p-2 text-xs"
          placeholder="What to ask the human."
        />
      )}
      {step.type === "output" && (
        <p className="text-xs text-muted-foreground">
          Shown here. On a real account: email, WhatsApp, Telegram, Google Drive, a webhook.
        </p>
      )}
      {state?.summary && status !== "idle" && (
        <p className="mt-2 border-t border-border/40 pt-2 text-[11px] text-muted-foreground">{state.summary}</p>
      )}
    </div>
  );
}
