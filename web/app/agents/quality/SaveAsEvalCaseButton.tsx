"use client";

import { useEffect, useState } from "react";
import { Check, FlaskConical, Loader2 } from "lucide-react";
import { Button } from "../../components/ui/button";
import { cn } from "../../lib/utils";
import {
  isEvalHookUnsupported,
  markEvalHookUnsupportedIf,
  resolveEvalApi,
  resolveEvalHooksApi,
  type EvalDatasetSummary,
} from "./evalDatasetsApi";
import { Select } from "../../components/ui/select";
import { useTranslations } from "../../lib/i18n";

/**
 * "Save as eval case" - turns one finished assistant turn into a golden case
 * in an eval dataset (POST .../quality/datasets/{datasetId}/cases/import).
 * Rendered under assistant turns by ChatPanel and AgentPlayground; lives in
 * the quality route folder because that is where the eval client contract
 * and the dataset editor it feeds already are.
 *
 * Three ways this renders NOTHING, all deliberate:
 *  - api.ts lacks the hook methods (older web build) - resolveEvalHooksApi
 *    returns null;
 *  - the core answered 404 (route not mounted) or 403 (FREE plan) to a
 *    previous import or dataset list on this page - the sticky
 *    isEvalHookUnsupported memo, see evalDatasetsApi.ts; and
 *  - there is no turnRef (an on-device turn never reached the server, so
 *    there is nothing persisted for the backend to import).
 * A missing feature is not an error state for a chat surface, so the chat
 * panel simply looks as it always did in each of those cases.
 *
 * Flow: click -> lazy-load the agent's datasets (a cheap list call, only
 * paid on first use, never on render) -> inline picker (native <select>, a
 * "new dataset" default when the agent has none) -> import -> a brief
 * "Saved" confirmation. Plain English strings, same as the eval section it
 * feeds (web/messages/*.json is owned by concurrent work - see
 * EvalDatasetsSection's i18n note).
 */

const NEW_DATASET_VALUE = "__new__";
const DEFAULT_NEW_DATASET_NAME = "Saved from chat";

export default function SaveAsEvalCaseButton({
  agentId,
  sessionId,
  turnRef,
  userMessage,
  agentResponse,
  className,
}: {
  agentId: string;
  sessionId: string | null;
  turnRef: string | null | undefined;
  userMessage?: string;
  agentResponse?: string;
  className?: string;
}) {
    const tHC = useTranslations("hcAgentsQualitySaveAsEvalCaseButton");
  const [hooksApi] = useState(() => resolveEvalHooksApi());
  const [evalApi] = useState(() => resolveEvalApi());
  const [phase, setPhase] = useState<"idle" | "loading" | "picking" | "saving" | "saved">("idle");
  const [datasets, setDatasets] = useState<EvalDatasetSummary[]>([]);
  const [selected, setSelected] = useState<string>(NEW_DATASET_VALUE);
  const [error, setError] = useState<string | null>(null);
  // Re-render trigger for the module-level unsupported memo: another
  // instance on the page can flip it while this one is mounted.
  const [hidden, setHidden] = useState(() => isEvalHookUnsupported("importEvalDatasetCase"));

  useEffect(() => {
    if (phase === "saved") {
      const timer = setTimeout(() => setPhase("idle"), 2500);
      return () => clearTimeout(timer);
    }
    return undefined;
  }, [phase]);

  if (!hooksApi || !evalApi || hidden || !turnRef) return null;

  async function openPicker() {
    setError(null);
    setPhase("loading");
    try {
      const list = await evalApi.listEvalDatasets(agentId);
      const rows = list || [];
      setDatasets(rows);
      setSelected(rows[0]?.id ?? NEW_DATASET_VALUE);
      setPhase("picking");
    } catch (err) {
      // A plan gate on the LIST is the same "not for this tenant" answer
      // an import 403 would give - hide rather than show a dead picker.
      if (markEvalHookUnsupportedIf("importEvalDatasetCase", err)) {
        setHidden(true);
        return;
      }
      setError((err as Error)?.message || "Couldn't load eval datasets.");
      setPhase("idle");
    }
  }

  async function save() {
    setError(null);
    setPhase("saving");
    try {
      let datasetId = selected;
      if (datasetId === NEW_DATASET_VALUE) {
        const created = await evalApi.createEvalDataset(agentId, {
          name: DEFAULT_NEW_DATASET_NAME,
          description: "Cases saved from live chat turns. Rename or edit on the Agent Quality page.",
        });
        datasetId = created.id;
      }
      await hooksApi.importEvalDatasetCase(agentId, datasetId, {
        sessionId,
        turnRef,
        userMessage,
        agentResponse,
      });
      setPhase("saved");
    } catch (err) {
      if (markEvalHookUnsupportedIf("importEvalDatasetCase", err)) {
        // Route not mounted on this core (404) - hide for good, quietly.
        setHidden(true);
        return;
      }
      setError((err as Error)?.message || "Couldn't save this turn as an eval case.");
      setPhase("picking");
    }
  }

  if (phase === "saved") {
    return (
      <span className={cn("inline-flex items-center gap-1 text-xs text-muted-foreground", className)}>
        <Check size={12} className="size-3" aria-hidden="true" />
        {tHC("hcAgentsQualitySaveAsEvalCaseButton.s4")}
      </span>
    );
  }

  if (phase === "picking" || phase === "saving") {
    return (
      <div className={cn("flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground", className)}>
        <label className="sr-only" htmlFor={`eval-ds-${turnRef}`}>
          {tHC("hcAgentsQualitySaveAsEvalCaseButton.s3")}
        </label>
        <Select
          id={`eval-ds-${turnRef}`}
          className="w-auto min-w-40 [&_button]:h-7 [&_button]:text-xs"
          value={selected}
          onValueChange={setSelected}
          disabled={phase === "saving"}
          options={[
            ...datasets.map((d) => ({
              value: d.id,
              label: `${d.name} (${d.caseCount} case${d.caseCount === 1 ? "" : "s"})`,
            })),
            { value: NEW_DATASET_VALUE, label: `New dataset: "${DEFAULT_NEW_DATASET_NAME}"` },
          ]}
        />
        <Button type="button" size="sm" onClick={save} disabled={phase === "saving"}>
          {phase === "saving" ? <Loader2 size={12} className="size-3 animate-spin" aria-hidden="true" /> : null}
          Save
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-7"
          onClick={() => {
            setPhase("idle");
            setError(null);
          }}
          disabled={phase === "saving"}
        >
          Cancel
        </Button>
        {error && (
          <span className="text-destructive" role="alert">
            {error}
          </span>
        )}
      </div>
    );
  }

  return (
    <span className={cn("inline-flex items-center gap-1.5", className)}>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="h-auto gap-1 px-1.5 py-0.5 text-xs font-normal text-muted-foreground"
        onClick={openPicker}
        disabled={phase === "loading"}
        title={tHC("hcAgentsQualitySaveAsEvalCaseButton.s2")}
      >
        {phase === "loading" ? (
          <Loader2 size={12} className="size-3 animate-spin" aria-hidden="true" />
        ) : (
          <FlaskConical size={12} className="size-3" aria-hidden="true" />
        )}
        {tHC("hcAgentsQualitySaveAsEvalCaseButton.s1")}
      </Button>
      {error && (
        <span className="text-xs text-destructive" role="alert">
          {error}
        </span>
      )}
    </span>
  );
}
