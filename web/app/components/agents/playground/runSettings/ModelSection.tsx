"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { TriangleAlert } from "lucide-react";
import { api } from "../../../../lib/api";
import type { ProviderModelInfo } from "../../../../lib/api/types/providers";
import { useTranslations } from "../../../../lib/i18n";
import { Alert, AlertDescription } from "../../../ui/alert";
import { Badge } from "../../../ui/badge";
import ModelInfoCard from "../../ModelInfoCard";
import { agentBehaviorHref, droppedSamplingParams, type SamplingParam } from "../runSettingsModel";
import type { PlaygroundAgent } from "../types";

const DEPTHS = ["FAST", "PLANNED", "CAREFUL", "RIGOROUS"] as const;

const NUMBER = new Intl.NumberFormat();

function Row({ label, value, testId }: { label: string; value: string; testId: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1" data-testid={testId}>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="m-0 min-w-0 truncate text-end text-sm tabular-nums text-foreground">{value}</dd>
    </div>
  );
}

/**
 * Model, reasoning depth and sampling this agent runs with. Read-only: the editor is the Behavior tab. A setting
 * the provider will drop for this model carries a warning, from the model catalog's own list of ignored
 * parameters (nothing is guessed when the catalog does not say).
 */
export function ModelSection({ agent }: { agent: PlaygroundAgent }) {
  const t = useTranslations("agentPlayground");
  const tw = useTranslations("agentWorkbench");
  const [model, setModel] = useState<ProviderModelInfo | undefined>(undefined);

  const provider = agent.providerName ?? null;
  const modelId = agent.model ?? null;
  useEffect(() => {
    setModel(undefined);
    if (!provider || !modelId) return undefined;
    let cancelled = false;
    Promise.resolve()
      .then(() => api.listProviderModels(provider))
      .then((models: ProviderModelInfo[]) => {
        if (!cancelled && Array.isArray(models)) setModel(models.find((m) => m.modelId === modelId));
      })
      .catch(() => {
        // The catalog is a bonus: without it the drawer still shows every setting, just no model blurb or warning.
      });
    return () => {
      cancelled = true;
    };
  }, [provider, modelId]);

  const depth = (DEPTHS as readonly string[]).includes(agent.reasoningDepth ?? "")
    ? (agent.reasoningDepth as (typeof DEPTHS)[number])
    : null;
  const unset = t("rsModelDefault");
  const num = (v: number | null | undefined) => (v == null ? unset : NUMBER.format(v));
  const dropped = droppedSamplingParams(agent, model);
  const paramLabel = (p: SamplingParam) => t(`rsParam_${p}`);
  const stops = agent.stopSequences?.length ?? 0;

  return (
    <section aria-labelledby="rs-model-h" data-testid="run-settings-model" className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <h3 id="rs-model-h" className="text-sm font-semibold text-foreground">
          {t("rsModelHeading")}
        </h3>
        <Link
          href={agentBehaviorHref(agent.id)}
          className="text-xs font-medium text-primary underline-offset-4 hover:underline"
        >
          {t("rsOpenBehavior")}
        </Link>
      </div>
      <dl className="m-0 divide-y divide-border rounded-lg border border-border bg-background/50 px-3">
        <Row
          testId="rs-model"
          label={t("rsModel")}
          value={provider && modelId ? `${provider} / ${modelId}` : t("rsModelNotSet")}
        />
        <Row testId="rs-depth" label={t("rsDepth")} value={depth ? tw(`reasoningDepth${depth}`) : unset} />
        <Row testId="rs-temperature" label={t("rsParam_temperature")} value={num(agent.temperature)} />
        <Row testId="rs-topP" label={t("rsParam_topP")} value={num(agent.topP)} />
        <Row testId="rs-maxTokens" label={t("rsParam_maxTokens")} value={num(agent.maxTokens)} />
        {agent.topK != null && <Row testId="rs-topK" label={t("rsParam_topK")} value={num(agent.topK)} />}
        {agent.frequencyPenalty != null && (
          <Row testId="rs-frequencyPenalty" label={t("rsParam_frequencyPenalty")} value={num(agent.frequencyPenalty)} />
        )}
        {agent.presencePenalty != null && (
          <Row testId="rs-presencePenalty" label={t("rsParam_presencePenalty")} value={num(agent.presencePenalty)} />
        )}
        {agent.seed != null && <Row testId="rs-seed" label={t("rsParam_seed")} value={num(agent.seed)} />}
        {stops > 0 && <Row testId="rs-stop" label={t("rsParam_stopSequences")} value={num(stops)} />}
      </dl>
      {depth && depth !== "FAST" && (
        <p className="m-0 text-xs text-muted-foreground" data-testid="rs-depth-hint">
          {tw(`reasoningDepth${depth}Hint`)} {tw("reasoningDepthCostNote")}
        </p>
      )}
      <div className="flex flex-wrap gap-1.5">
        <Badge variant="secondary">{t("rsSourceAgent")}</Badge>
      </div>
      {dropped.length > 0 && (
        <Alert variant="warning" data-testid="rs-dropped-warning">
          <TriangleAlert aria-hidden="true" />
          <AlertDescription>
            <ul className="m-0 list-none space-y-0.5 p-0">
              {dropped.map((p) => (
                <li key={p}>
                  {t("rsParamDropped", { param: paramLabel(p), model: modelId ?? "", provider: provider ?? "" })}
                </li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      )}
      <ModelInfoCard model={model} />
    </section>
  );
}
