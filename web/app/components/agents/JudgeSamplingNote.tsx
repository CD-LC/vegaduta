"use client";

import { useEffect, useState } from "react";
import { Lock } from "lucide-react";
import { Badge } from "../ui/badge";
import { useTranslations } from "../../lib/i18n";
import { settingsApi, type SettingsClient } from "../../lib/api/settings";
import { judgeSamplingFrom, type JudgeSampling } from "./judgeSampling";

type State = { kind: "loading" } | { kind: "ready"; sampling: JudgeSampling } | { kind: "unavailable" };

/**
 * Read-only: how many live turns the background quality judge scores, and where that number comes from. The rate
 * is an operator-level setting; when the settings API does not publish it the note says so instead of guessing.
 */
export default function JudgeSamplingNote({ agentId, client = settingsApi }: { agentId: string; client?: SettingsClient }) {
  const t = useTranslations("evalControls");
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "loading" });
    client
      .getEffective(agentId)
      .then((effective) => {
        if (cancelled) return;
        const sampling = judgeSamplingFrom(effective);
        setState(sampling ? { kind: "ready", sampling } : { kind: "unavailable" });
      })
      .catch(() => {
        if (!cancelled) setState({ kind: "unavailable" });
      });
    return () => {
      cancelled = true;
    };
  }, [agentId, client]);

  return (
    <div className="space-y-1 rounded-md border border-border p-3" data-testid="judge-sampling">
      <h3 className="text-sm font-semibold text-foreground">{t("samplingTitle")}</h3>
      {state.kind === "loading" && (
        <p role="status" className="text-sm text-muted-foreground">
          {t("samplingLoading")}
        </p>
      )}
      {state.kind === "unavailable" && <p className="text-sm text-muted-foreground">{t("samplingUnavailable")}</p>}
      {state.kind === "ready" && (
        <>
          <p className="flex flex-wrap items-center gap-2 text-sm text-foreground">
            <span>{t("samplingValue", { percent: state.sampling.percent })}</span>
            <Badge variant="outline" data-testid="judge-sampling-source">
              {state.sampling.locked && <Lock aria-hidden="true" />}
              {t(`samplingSource_${state.sampling.source}`)}
            </Badge>
          </p>
          <p className="text-xs text-muted-foreground">{t("samplingHelp")}</p>
        </>
      )}
    </div>
  );
}
