"use client";

import { useMemo, type ReactNode } from "react";
import { Gauge } from "lucide-react";
import type { EffectiveSetting, SettingCatalogEntry } from "../../../lib/api/settings";
import { useTranslations } from "../../../lib/i18n";
import SourceBadge from "../../settings/SourceBadge";
import { useSettingsText } from "../../settings/useSettingsText";
import { Alert, AlertDescription } from "../../ui/alert";
import { Button } from "../../ui/button";
import { Skeleton } from "../../ui/skeleton";
import { BEHAVIOR_KEYS, impactLines, overriddenKeys } from "./behaviorModel";

export type SummaryStatus = "loading" | "ready" | "error";

interface Fact {
  id: string;
  label: string;
  value: ReactNode;
  /** Catalog key whose source badge is shown next to the value; absent for facts that are not settings. */
  settingKey?: string;
}

/**
 * "How this agent behaves right now": the values in force for ONE agent after its own overrides, the workspace
 * values and the platform values are applied, each with the layer it comes from, followed by plain sentences on
 * what they mean. It is a live region: when a value is saved or reset in the panel below, the card updates.
 *
 * Everything comes from the catalog + effective values handed in; a setting an older core does not have is
 * simply left out. The model and the autonomy level are not settings, they come from the agent itself.
 */
export default function EffectiveBehaviorCard({
  status,
  error,
  entries,
  values,
  model,
  autonomyTier,
  onRetry,
}: {
  status: SummaryStatus;
  error?: string | null;
  entries: SettingCatalogEntry[];
  values: Record<string, EffectiveSetting>;
  /** Provider and model the agent runs on, e.g. "OPENAI / gpt-x"; empty when unknown. */
  model?: string;
  autonomyTier?: string | null;
  onRetry?: () => void;
}) {
  const t = useTranslations("agentBehavior");
  const text = useSettingsText();
  const byKey = useMemo(() => Object.fromEntries(entries.map((e) => [e.key, e])), [entries]);

  const facts = useMemo<Fact[]>(() => {
    const out: Fact[] = [];
    const settingFact = (
      id: string,
      label: string,
      key: string,
      render?: (e: SettingCatalogEntry, v: unknown) => string
    ) => {
      const entry = byKey[key];
      const eff = values[key];
      if (!entry || !eff) return;
      out.push({
        id,
        label,
        settingKey: key,
        value: render ? render(entry, eff.value) : text.formatValue(entry, eff.value),
      });
    };
    settingFact("steps", t("summary.facts.steps"), BEHAVIOR_KEYS.maxSteps);
    settingFact("turnTimeout", t("summary.facts.turnTimeout"), BEHAVIOR_KEYS.turnTimeout);
    settingFact("toolTimeout", t("summary.facts.toolTimeout"), BEHAVIOR_KEYS.toolTimeout);
    settingFact("outputCap", t("summary.facts.outputCap"), BEHAVIOR_KEYS.outputCap);
    settingFact("continuation", t("summary.facts.continuation"), BEHAVIOR_KEYS.continuation);
    if (autonomyTier || (byKey[BEHAVIOR_KEYS.approvalContinuation] && values[BEHAVIOR_KEYS.approvalContinuation])) {
      const tier = autonomyTier ? t(`summary.tier.${autonomyTier}`) : "";
      const entry = byKey[BEHAVIOR_KEYS.approvalContinuation];
      const eff = values[BEHAVIOR_KEYS.approvalContinuation];
      const after =
        entry && eff
          ? eff.value === true || eff.value === "true"
            ? t("summary.approvalResumes")
            : t("summary.approvalStops")
          : "";
      out.push({
        id: "approvals",
        label: t("summary.facts.approvals"),
        settingKey: entry && eff ? BEHAVIOR_KEYS.approvalContinuation : undefined,
        value: [tier, after].filter(Boolean).join(". "),
      });
    }
    settingFact("judge", t("summary.facts.judge"), BEHAVIOR_KEYS.judgeModel, (_entry, v) =>
      typeof v === "string" && v.trim() !== "" ? v : t("summary.judgeDefault")
    );
    if (model) out.push({ id: "model", label: t("summary.facts.model"), value: model });
    return out;
  }, [byKey, values, text, t, autonomyTier, model]);

  const lines = useMemo(
    () => impactLines({ values, entries: byKey, format: text.formatValue, autonomyTier }),
    [values, byKey, text.formatValue, autonomyTier]
  );
  const overrides = useMemo(() => overriddenKeys(entries, values), [entries, values]);

  return (
    <section
      aria-labelledby="agent-behavior-summary-title"
      className="rounded-xl border border-border bg-card p-4"
      data-testid="effective-behavior-card"
    >
      <div className="flex items-center gap-2">
        <span className="cat-chip cat-chip-sm" style={{ ["--cat" as string]: "var(--cat-analysis)" }}>
          <Gauge size={12} aria-hidden="true" />
        </span>
        <h3 id="agent-behavior-summary-title" className="text-sm font-semibold">
          {t("summary.title")}
        </h3>
      </div>
      <p className="mt-1 text-xs text-muted-foreground">{t("summary.subtitle")}</p>

      {status === "loading" ? (
        <div
          role="status"
          aria-busy="true"
          aria-label={t("summary.loading")}
          className="mt-3 grid gap-2 sm:grid-cols-2"
          data-testid="summary-loading"
        >
          <Skeleton className="h-14" />
          <Skeleton className="h-14" />
          <Skeleton className="h-14" />
          <Skeleton className="h-14" />
        </div>
      ) : status === "error" ? (
        <Alert variant="destructive" className="mt-3" data-testid="summary-error">
          <AlertDescription>
            <span>{t("summary.error", { message: error ?? "" })}</span>
            {onRetry ? (
              <Button type="button" size="sm" variant="outline" onClick={onRetry}>
                {t("summary.retry")}
              </Button>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : facts.length === 0 ? (
        <p className="mt-3 text-sm text-muted-foreground" data-testid="summary-empty">
          {t("summary.empty")}
        </p>
      ) : (
        <div aria-live="polite" data-testid="summary-live">
          <dl className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
            {facts.map((f) => (
              <div key={f.id} className="elev-sunken rounded-lg p-3" data-testid={`summary-fact-${f.id}`}>
                <dt className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
                  {f.label}
                  {f.settingKey && values[f.settingKey] ? (
                    <SourceBadge
                      source={values[f.settingKey].source}
                      scope="agent"
                      locked={values[f.settingKey].locked}
                    />
                  ) : null}
                </dt>
                <dd className="mt-1 break-words text-sm font-semibold text-foreground">{f.value}</dd>
              </div>
            ))}
          </dl>

          <p className="mt-3 text-xs text-muted-foreground" data-testid="summary-overrides">
            {overrides.length === 0
              ? t("summary.overrides.none")
              : t("summary.overrides.some", { count: overrides.length })}
          </p>

          {lines.length > 0 ? (
            <div className="mt-3">
              <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                {t("impact.title")}
              </h4>
              <ul className="mt-1 list-disc space-y-1 ps-5 text-sm" data-testid="impact-list">
                {lines.map((l) => (
                  <li key={l.id} data-testid={`impact-${l.id}`}>
                    {t(l.messageKey, l.params)}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      )}
    </section>
  );
}
