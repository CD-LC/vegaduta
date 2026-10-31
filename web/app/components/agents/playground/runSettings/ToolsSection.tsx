"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { api } from "../../../../lib/api";
import { errorMessage } from "../../../../lib/errors";
import { useTranslations } from "../../../../lib/i18n";
import { Badge } from "../../../ui/badge";
import { Button } from "../../../ui/button";
import { Skeleton } from "../../../ui/skeleton";
import { Switch } from "../../../ui/switch";
import { canEditAgent } from "../../agentAccess";
import {
  agentToolNames,
  agentToolsHref,
  effectiveRunTools,
  flipRunTool,
  toolRows,
} from "../runSettingsModel";
import type { PlaygroundAgent } from "../types";

type Available = Array<{ name: string; description?: string | null }>;

/**
 * One switch per tool the agent has. These switches are for THIS RUN ONLY: they choose which of the agent's tools the
 * messages sent from this playground may use, and nothing is saved. The agent's own tool access (the list on its Tools
 * tab, which every user and channel shares) is not touched, and there is no way to change it from here - the link
 * below goes to where it is edited.
 *
 * It can only narrow. A tool the saved agent does not have is shown off and cannot be switched on, and turning every
 * switch off is allowed (the run then has no tools). `runTools` is the selection the playground keeps for the session
 * and sends with each message as `toolNames`; `null` means "no selection, the agent's own tools". The reset button
 * returns to that.
 */
export function ToolsSection({
  agent,
  runTools,
  onRunToolsChange,
}: {
  agent: PlaygroundAgent;
  /** This session's tool selection for runs, or `null` for the agent's own tools. */
  runTools: string[] | null;
  /** Called with the new selection (`null` = back to the agent's own tools). Nothing else happens: no request is made. */
  onRunToolsChange: (names: string[] | null) => void;
}) {
  const t = useTranslations("agentPlayground");
  const tRef = useRef(t);
  tRef.current = t;
  const [available, setAvailable] = useState<Available | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const editable = canEditAgent(agent);

  const load = useCallback(() => {
    let cancelled = false;
    setAvailable(null);
    setLoadError(null);
    Promise.resolve()
      .then(() => api.listAvailableTools(agent.id))
      .then((tools: Available) => {
        if (!cancelled) setAvailable(Array.isArray(tools) ? tools : []);
      })
      .catch((e: unknown) => {
        if (!cancelled) setLoadError(errorMessage(e, tRef.current("rsToolsFailed")));
      });
    return () => {
      cancelled = true;
    };
  }, [agent.id]);

  useEffect(() => load(), [load]);

  // Prune a stale run selection the moment the offered list arrives: a tool
  // removed from the agent (or left over from a previous agent's selection)
  // would otherwise stay checked and keep 400ing every send. Dropping unknown
  // names here means the selection self-heals on every load - no version key
  // needed. Only fires when something was actually stale, never on a clean
  // selection.
  useEffect(() => {
    if (!available || runTools === null) return;
    const offered = new Set(available.map((x) => x.name));
    const kept = runTools.filter((n) => offered.has(n));
    if (kept.length !== runTools.length) onRunToolsChange(kept.length > 0 ? kept : null);
    // runTools/available/onRunToolsChange identity is what drives this; a
    // change to any of them re-evaluates pruning correctly.
  }, [available, runTools, onRunToolsChange]);

  // `enabled` on a row is whether the SAVED agent has the tool; whether the run uses it is the selection on top.
  const rows = toolRows(available, agent.allowedToolNames ?? []);
  const agentNames = agentToolNames(rows);
  const runNames = new Set(effectiveRunTools(agentNames, runTools));
  const narrowed = runTools !== null;

  return (
    <section aria-labelledby="rs-tools-h" data-testid="run-settings-tools" className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id="rs-tools-h" className="text-sm font-semibold text-foreground">
          {t("rsToolsRunHeading")}
        </h3>
        <Badge variant="accent" data-testid="rs-tools-run-only">
          {t("rsToolsRunOnly")}
        </Badge>
      </div>
      <p className="m-0 text-xs text-muted-foreground">{t("rsToolsRunNote")}</p>
      {available === null && !loadError && (
        <div role="status" aria-label={t("rsLoading")} className="space-y-1.5">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      )}
      {loadError && (
        <div role="alert" className="flex flex-wrap items-center gap-2 text-xs text-destructive">
          <span className="min-w-0">{loadError}</span>
          <Button type="button" size="sm" variant="outline" onClick={load}>
            {t("rsRetry")}
          </Button>
        </div>
      )}
      {available !== null && rows.length === 0 && (
        <p className="m-0 text-xs text-muted-foreground" data-testid="rs-tools-empty">
          {t("rsToolsEmpty")}
        </p>
      )}
      {rows.length > 0 && (
        <>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="m-0 text-xs text-foreground" data-testid="rs-tools-summary">
              {narrowed
                ? t("rsToolsRunSummary", { on: runNames.size, total: agentNames.length })
                : t("rsToolsSummary", { on: agentNames.length, total: rows.length })}
            </p>
            {narrowed && (
              <Button type="button" size="sm" variant="outline" onClick={() => onRunToolsChange(null)} data-testid="rs-tools-reset">
                {t("rsToolsReset")}
              </Button>
            )}
          </div>
          {narrowed && runNames.size === 0 && (
            <p className="m-0 text-xs text-muted-foreground" data-testid="rs-tools-none">
              {t("rsToolsRunNone")}
            </p>
          )}
          <ul className="m-0 list-none divide-y divide-border rounded-lg border border-border bg-background/50 p-0 px-3">
            {rows.map((row) => {
              const id = `rs-tool-${row.name}`;
              const agentHasIt = row.enabled;
              return (
                <li key={row.name} className="flex items-start gap-3 py-2" data-testid={id}>
                  <div className="min-w-0 flex-1">
                    <label htmlFor={`${id}-switch`} className="block break-all font-mono text-xs text-foreground">
                      {row.name}
                    </label>
                    {row.description && (
                      <p id={`${id}-desc`} className="m-0 line-clamp-2 text-[11px] text-muted-foreground">
                        {row.description}
                      </p>
                    )}
                    {!agentHasIt && (
                      <p id={`${id}-off`} className="m-0 text-[11px] text-muted-foreground" data-testid={`${id}-not-in-agent`}>
                        {t("rsToolsNotInAgent")}
                      </p>
                    )}
                  </div>
                  <Switch
                    id={`${id}-switch`}
                    checked={agentHasIt && runNames.has(row.name)}
                    disabled={!agentHasIt}
                    aria-describedby={!agentHasIt ? `${id}-off` : row.description ? `${id}-desc` : undefined}
                    onCheckedChange={() => onRunToolsChange(flipRunTool(agentNames, runTools, row.name))}
                  />
                </li>
              );
            })}
          </ul>
        </>
      )}
      {editable && (
        <p className="m-0 text-xs text-muted-foreground">
          <Link
            href={agentToolsHref(agent.id)}
            data-testid="rs-tools-edit-agent"
            className="font-medium text-primary underline-offset-4 hover:underline"
          >
            {t("rsToolsEditAgent")}
          </Link>
        </p>
      )}
    </section>
  );
}
