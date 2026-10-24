"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { api } from "../../../../lib/api";
import { agentRuntimeLimitsApi, type AgentRuntimeLimitsView } from "../../../../lib/api/agentRuntimeLimits";
import {
  createSettingsClient,
  type EffectiveSettings,
  type SettingCatalogEntry,
  type SettingsClient,
} from "../../../../lib/api/settings";
import { errorMessage } from "../../../../lib/errors";
import { useTranslations } from "../../../../lib/i18n";
import { Badge } from "../../../ui/badge";
import { Button } from "../../../ui/button";
import { Skeleton } from "../../../ui/skeleton";
import SourceBadge from "../../../settings/SourceBadge";
import { useSettingsText } from "../../../settings/useSettingsText";
import { canEditAgent } from "../../agentAccess";
import { formatRunBudgetUsd, normalizeRunBudget, type AgentRunBudget } from "../../agentRunBudget";
import { agentBehaviorHref, limitRows, type LimitRow } from "../runSettingsModel";
import type { PlaygroundAgent } from "../types";

const NUMBER = new Intl.NumberFormat();

type Translate = (key: string, vars?: Record<string, string | number>) => string;

/** The settings-catalog keys this section shows: the agent runtime limits. */
const LIMIT_KEY_PREFIX = "agent.limits.";

let defaultSettingsClient: SettingsClient | null = null;
function sharedSettingsClient(): SettingsClient {
  defaultSettingsClient ??= createSettingsClient();
  return defaultSettingsClient;
}

type LoadState =
  | { status: "loading" }
  | { status: "error"; message: string }
  // The settings catalog and what is in force for this agent: bounds, units, wording and the source of each
  // value all come from the server.
  | { status: "ready"; mode: "catalog"; entries: SettingCatalogEntry[]; effective: EffectiveSettings }
  // A core without the settings catalog: the older runtime-limits view.
  | { status: "ready"; mode: "legacy"; view: AgentRuntimeLimitsView };

function valueText(t: Translate, row: LimitRow): string {
  switch (row.unit) {
    case "seconds":
      return row.value === 0 ? t("rsNoLimit") : t("rsUnitSeconds", { n: NUMBER.format(row.value) });
    case "chars":
      return t("rsUnitChars", { n: NUMBER.format(row.value) });
    case "score":
      return row.value === 0 ? t("rsScoreAll") : row.value.toFixed(2);
    default:
      return NUMBER.format(row.value);
  }
}

/**
 * The limits a turn of this agent runs under (tool calls per turn, timeouts, output and retrieval limits), each
 * with the layer it comes from. Read-only here; "Change" goes to the Behavior tab, where an editor can raise or
 * reset them.
 */
export function LimitsSection({
  agent,
  settingsClient,
}: {
  agent: PlaygroundAgent;
  /** Test seam: the settings-catalog client. Defaults to the authenticated one. */
  settingsClient?: SettingsClient;
}) {
  const t = useTranslations("agentPlayground");
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const agentId = agent.id;
  // `t` and the client are read through refs: a translator or client that changes identity must not restart
  // the load.
  const tRef = useRef(t);
  tRef.current = t;
  const clientRef = useRef(settingsClient);
  clientRef.current = settingsClient;

  const load = useCallback(() => {
    let cancelled = false;
    setState({ status: "loading" });
    const client = clientRef.current ?? sharedSettingsClient();
    const legacy = () =>
      Promise.resolve()
        .then(() => agentRuntimeLimitsApi.getAgentRuntimeLimits(agentId))
        .then((view): LoadState => ({ status: "ready", mode: "legacy", view }));
    // The catalog first. No catalog (an older core, or no access to it) or none of the limits in it: the limits
    // are still readable the old way.
    Promise.all([client.getCatalog(), client.getEffective(agentId)])
      .then(
        ([catalog, effective]): LoadState | null => {
          const entries = catalog.entries.filter(
            (e) => e.key.startsWith(LIMIT_KEY_PREFIX) && effective.values[e.key] !== undefined
          );
          return entries.length > 0 ? { status: "ready", mode: "catalog", entries, effective } : null;
        },
        (): LoadState | null => null
      )
      .then((next) => next ?? legacy())
      .then((next) => {
        if (!cancelled) setState(next);
      })
      .catch((e: unknown) => {
        if (!cancelled) setState({ status: "error", message: errorMessage(e, tRef.current("rsLimitsFailed")) });
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  useEffect(() => load(), [load]);

  // The spending cap per run is a limit too; it has its own endpoint and hides on any failure (older core, no
  // access, per-agent caps switched off), so the section never shows an error for it.
  const [budget, setBudget] = useState<AgentRunBudget | null>(null);
  useEffect(() => {
    let cancelled = false;
    setBudget(null);
    Promise.resolve()
      .then(() => api.getAgentRunBudget(agentId))
      .then((raw: unknown) => {
        if (!cancelled) setBudget(normalizeRunBudget(raw));
      })
      .catch(() => {
        // Optional row: leave it out.
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const editable = canEditAgent(agent);
  const legacyRows = state.status === "ready" && state.mode === "legacy" ? limitRows(state.view) : [];
  const catalogEntries = state.status === "ready" && state.mode === "catalog" ? state.entries : [];

  return (
    <section aria-labelledby="rs-limits-h" data-testid="run-settings-limits" className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <h3 id="rs-limits-h" className="text-sm font-semibold text-foreground">
          {t("rsLimitsHeading")}
        </h3>
        <Link
          href={agentBehaviorHref(agentId, "agent.limits")}
          data-testid="rs-limits-link"
          className="text-xs font-medium text-primary underline-offset-4 hover:underline"
        >
          {editable ? t("rsLimitsChange") : t("rsLimitsView")}
        </Link>
      </div>
      {state.status === "loading" && (
        <div role="status" aria-label={t("rsLoading")} className="space-y-1.5">
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
          <Skeleton className="h-6 w-full" />
        </div>
      )}
      {state.status === "error" && (
        <div role="alert" className="flex flex-wrap items-center gap-2 text-xs text-destructive">
          <span className="min-w-0">{state.message}</span>
          <Button type="button" size="sm" variant="outline" onClick={load}>
            {t("rsRetry")}
          </Button>
        </div>
      )}
      {state.status === "ready" && legacyRows.length === 0 && catalogEntries.length === 0 && !budget && (
        <p className="m-0 text-xs text-muted-foreground">{t("rsLimitsEmpty")}</p>
      )}
      {(legacyRows.length > 0 || catalogEntries.length > 0 || budget) && (
        <ul className="m-0 list-none divide-y divide-border rounded-lg border border-border bg-background/50 p-0 px-3">
          {state.status === "ready" && state.mode === "catalog" && (
            <CatalogLimitRows agentId={agentId} entries={state.entries} effective={state.effective} editable={editable} />
          )}
          {legacyRows.map((row) => (
            <li key={row.name} className="py-2" data-testid={`rs-limit-${row.name}`}>
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-xs text-foreground">{t(`rsLimit_${row.name}`)}</span>
                <span className="text-sm font-medium tabular-nums text-foreground">{valueText(t, row)}</span>
              </div>
              <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
                <Badge variant={row.overridden ? "accent" : "secondary"} data-testid={`rs-limit-${row.name}-layer`}>
                  {t(`rsLayer_${row.layer}`)}
                </Badge>
                {row.min != null && row.max != null && (
                  <span className="text-[11px] text-muted-foreground">
                    {t("rsLimitRange", { min: NUMBER.format(row.min), max: NUMBER.format(row.max) })}
                  </span>
                )}
              </div>
              <p className="m-0 mt-0.5 text-[11px] text-muted-foreground">{t(`rsLimitHelp_${row.name}`)}</p>
            </li>
          ))}
          {budget && (
            <li className="py-2" data-testid="rs-limit-runBudget">
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-xs text-foreground">{t("rsRunBudget")}</span>
                <span className="text-sm font-medium tabular-nums text-foreground">
                  {budget.effectiveCapUsd > 0 ? formatRunBudgetUsd(budget.effectiveCapUsd) : t("rsNoCap")}
                </span>
              </div>
              <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
                <Badge variant={budget.agentCapUsd != null ? "accent" : "secondary"} data-testid="rs-limit-runBudget-layer">
                  {budget.agentCapUsd != null ? t("rsLayer_AGENT") : t("rsLayer_PLATFORM")}
                </Badge>
              </div>
              <p className="m-0 mt-0.5 text-[11px] text-muted-foreground">{t("rsRunBudgetHelp")}</p>
            </li>
          )}
        </ul>
      )}
    </section>
  );
}

/**
 * One row per runtime limit of the settings catalog: the plain-language name, the value in its unit, the allowed
 * range, which layer the value comes from (default, platform, workspace or this agent), and - when an operator
 * pinned it - that it is locked and why. Wording, units and bounds are the catalog's; nothing is decided here.
 */
function CatalogLimitRows({
  agentId,
  entries,
  effective,
  editable,
}: {
  agentId: string;
  entries: SettingCatalogEntry[];
  effective: EffectiveSettings;
  editable: boolean;
}) {
  const t = useTranslations("agentPlayground");
  const text = useSettingsText();
  return (
    <>
      {entries.map((entry) => {
        const eff = effective.values[entry.key];
        const name = entry.key.slice(LIMIT_KEY_PREFIX.length);
        const noLimit = (entry.unit === "s" || entry.unit === "ms") && eff.value === 0;
        const description = text.description(entry);
        return (
          <li key={entry.key} className="py-2" data-testid={`rs-limit-${name}`}>
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-xs text-foreground">{text.label(entry)}</span>
              <span className="text-sm font-medium tabular-nums text-foreground">
                {noLimit ? t("rsNoLimit") : text.formatValue(entry, eff.value)}
              </span>
            </div>
            <div className="mt-0.5 flex flex-wrap items-center gap-1.5">
              <span data-testid={`rs-limit-${name}-layer`}>
                <SourceBadge source={eff.source} scope="agent" locked={eff.locked} />
              </span>
              {entry.min != null && entry.max != null && (
                <span className="text-[11px] text-muted-foreground">
                  {text.ui("hint.range", {
                    min: text.formatValue(entry, entry.min),
                    max: text.formatValue(entry, entry.max),
                  })}
                </span>
              )}
              {editable && !eff.locked && (
                <Link
                  href={agentBehaviorHref(agentId, entry.key)}
                  data-testid={`rs-limit-${name}-change`}
                  className="text-[11px] font-medium text-primary underline-offset-4 hover:underline"
                >
                  {t("rsLimitChangeOne")}
                </Link>
              )}
            </div>
            {eff.locked && (
              <p className="m-0 mt-0.5 text-[11px] text-muted-foreground" data-testid={`rs-limit-${name}-locked`}>
                {eff.lockedReason ? text.ui("locked.withReason", { reason: eff.lockedReason }) : text.ui("locked.noReason")}
              </p>
            )}
            {description && <p className="m-0 mt-0.5 text-[11px] text-muted-foreground">{description}</p>}
          </li>
        );
      })}
    </>
  );
}
