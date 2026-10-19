"use client";

import { useEffect, useState } from "react";
import { AlertTriangle, Loader2 } from "lucide-react";
import { api, type AgentCapabilityEntry } from "../../../lib/api";
import { useTranslations } from "../../../lib/i18n";
import { cn } from "../../../lib/utils";
import { SUB_TEXT } from "./styles";

/**
 * The generic, plug-and-play capabilities list - the UI half of
 * GET/PATCH /api/agents/{id}/capabilities and the replacement for the
 * retired provider-branded sections (Sarvam tools, E2B sandbox, xAI server
 * tools, code-workspace toggle). It renders WHATEVER the backend's
 * AgentCapability registry says applies to this agent - labels, descriptions
 * and availability are all server-supplied - so wiring a new provider or
 * platform capability requires zero changes here.
 *
 * Capability-specific configuration (e.g. the code-intel repo allow-list
 * managed under the Code Intelligence section) stays where it is; this list
 * owns only the on/off opt-in.
 */
export function AgentCapabilities({
  agentId,
  locked,
  onChanged,
  onError,
}: {
  /** null = the workbench is showing a not-yet-created draft. */
  agentId: string | null;
  locked: boolean;
  /** Refetch callback so the parent's agent object picks up flag-derived fields. */
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const t = useTranslations("agentWorkbench");
  const [capabilities, setCapabilities] = useState<AgentCapabilityEntry[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  useEffect(() => {
    if (!agentId) {
      setCapabilities(null);
      return undefined;
    }
    let cancelled = false;
    setLoadFailed(false);
    api
      .listAgentCapabilities(agentId)
      .then((list) => {
        if (!cancelled) setCapabilities(Array.isArray(list) ? list : []);
      })
      .catch(() => {
        // Same calm-degradation posture as the modality catalog: a missing
        // endpoint means "no capabilities to show", not a broken screen.
        if (!cancelled) {
          setCapabilities([]);
          setLoadFailed(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  async function toggle(capability: AgentCapabilityEntry) {
    if (!agentId || busyKey || locked) return;
    const next = !capability.enabled;
    setBusyKey(capability.key);
    try {
      await api.setAgentCapability(agentId, capability.key, next);
      setCapabilities((current) =>
        (current || []).map((entry) =>
          entry.key === capability.key ? { ...entry, enabled: next } : entry
        )
      );
      onChanged();
    } catch (err: any) {
      onError(err?.message || t("capabilityToggleError"));
    } finally {
      setBusyKey(null);
    }
  }

  if (!agentId) {
    return <p className={cn("italic", SUB_TEXT)}>{t("capabilitiesDraftHint")}</p>;
  }
  if (capabilities === null) {
    return (
      <p className={cn("flex items-center gap-2", SUB_TEXT)}>
        <Loader2 size={14} className="animate-spin" /> {t("capabilitiesLoading")}
      </p>
    );
  }
  if (capabilities.length === 0) {
    return (
      <p className={SUB_TEXT}>
        {loadFailed ? t("capabilitiesLoadError") : t("capabilitiesEmpty")}
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {capabilities.map((capability) => {
        const unavailable = capability.availability === "UNAVAILABLE";
        const needsSetup = capability.availability === "NEEDS_SETUP";
        return (
          <div
            key={capability.key}
            className="flex items-start justify-between gap-3 rounded-lg border border-border px-3 py-2.5"
          >
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium text-foreground">{capability.label}</span>
                {needsSetup && (
                  <span
                    className="state-pill"
                    style={{ ["--state" as any]: "var(--state-warning)" } as any}
                  >
                    {t("capabilityNeedsSetup")}
                  </span>
                )}
                {unavailable && (
                  <span
                    className="state-pill"
                    style={{ ["--state" as any]: "var(--text-muted)" } as any}
                  >
                    {t("capabilityUnavailable")}
                  </span>
                )}
              </div>
              <p className={cn("mt-0.5 text-xs", SUB_TEXT)}>{capability.description}</p>
              {(needsSetup || unavailable) && capability.availabilityHint && (
                <p className={cn("mt-1 flex items-start gap-1 text-xs", SUB_TEXT)}>
                  <AlertTriangle size={12} className="mt-0.5 shrink-0" />
                  {capability.availabilityHint}
                </p>
              )}
            </div>
            <button
              type="button"
              className="flex shrink-0 items-center gap-2 text-xs font-medium text-muted-foreground disabled:opacity-50"
              onClick={() => toggle(capability)}
              disabled={busyKey === capability.key || locked || unavailable}
              title={locked ? t("editLockedTooltip") : unavailable ? capability.availabilityHint || undefined : undefined}
            >
              {busyKey === capability.key && <Loader2 size={12} className="animate-spin" />}
              <span
                className={cn(
                  "ax-toggle ax-toggle-lg ax-toggle-violet",
                  capability.enabled && "ax-toggle-on"
                )}
              />
            </button>
          </div>
        );
      })}
    </div>
  );
}
