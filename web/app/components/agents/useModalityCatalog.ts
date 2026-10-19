"use client";

import { useEffect, useState } from "react";
import { api, type ModalityCatalog, type ModalityProviderOption } from "../../lib/api";
import type { TranscriptionProviderOption } from "./TranscriptionProviderOptions";

export type ModalityKind = "speechToText" | "textToSpeech" | "videoToText";

export type ProviderStatus = "available" | "unavailable" | "checking";

/**
 * Shared fetch behind the three modality selectors (voice STT / TTS / video)
 * - the client half of GET /api/providers/modalities. Replaced the
 * per-selector hardcoded provider arrays AND AgentWorkbench's hand-wired
 * availability probes (getSarvamCredential, getElevenLabsCredential, an
 * OPENAI-row scan of listProviders(), and a sidecar health read): every
 * option's label/description/model/availability now comes from the server,
 * so adding a modality provider is a backend-only change.
 *
 * The whole catalog is one response, so whichever selector mounts first
 * shares its fetch with the other two - the short-lived cache below is only
 * a mount-storm dedupe, not a long-lived cache (a tenant who just added a
 * credential on the Providers page expects the next workbench mount to
 * reflect it).
 */
let inflight: Promise<ModalityCatalog | null> | null = null;
let cached: { at: number; data: ModalityCatalog | null } | null = null;
const CACHE_TTL_MS = 30_000;

export function fetchModalityCatalog(): Promise<ModalityCatalog | null> {
  if (cached && Date.now() - cached.at < CACHE_TTL_MS) {
    return Promise.resolve(cached.data);
  }
  if (inflight === null) {
    inflight = (api
      .getModalityCatalog() as Promise<ModalityCatalog | null>)
      .then((data) => {
        cached = { at: Date.now(), data };
        return data;
      })
      .catch((): null => {
        // Fail quiet: the selectors degrade to their "Platform Default" row
        // alone rather than breaking the workbench over a missing endpoint.
        cached = { at: Date.now(), data: null };
        return null;
      })
      .finally(() => {
        inflight = null;
      });
  }
  return inflight;
}

/**
 * Maps one catalog row to the selector's option shape:
 *  - !implemented            -> greyed-out, non-interactive "Coming soon" row
 *    (replaces the old hardcoded `disabled: true` SARVAM_VIDEO entry).
 *  - implemented, !available -> selectable, but flagged "Needs setup" via
 *    requiresConfig + the status map (the credential/setup case).
 *  - premium                 -> filtered out for FREE plans, same flag the
 *    old arrays carried per-option.
 */
function toOption(option: ModalityProviderOption): TranscriptionProviderOption {
  return {
    id: option.provider,
    name: option.label,
    description: option.description,
    model: option.defaultModel || null,
    isPremium: option.premium,
    requiresConfig: option.premium,
    disabled: !option.implemented,
    disabledReason: option.implemented ? undefined : "Coming soon",
  };
}

/** Raw catalog access for callers that need provider->defaultModel lookups rather than rendered options (AgentWorkbench's save-path backfills). */
export function useModalityCatalogData(): ModalityCatalog | null {
  const [catalog, setCatalog] = useState<ModalityCatalog | null>(null);
  useEffect(() => {
    let cancelled = false;
    void fetchModalityCatalog().then((data) => {
      if (!cancelled) setCatalog(data);
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return catalog;
}

/** A catalog row's default model for the given provider enum name, or null. */
export function defaultModelFor(
  list: ModalityProviderOption[] | undefined,
  provider: string | null | undefined
): string | null {
  return list?.find((o) => o.provider === provider)?.defaultModel || null;
}

export interface ModalitySelectorData {
  /** Catalog options with the "Platform Default" (null) row prepended. */
  providers: TranscriptionProviderOption[];
  /** Per-provider availability, keyed by the enum name (same keys the options use as `id`). */
  providerStatus: Record<string, ProviderStatus>;
  /** Still fetching - lets a caller show the old "checking" spinner state. */
  loading: boolean;
}

export function useModalityCatalog(
  kind: ModalityKind,
  platformDefault: { name: string; description: string }
): ModalitySelectorData {
  const [catalog, setCatalog] = useState<ModalityCatalog | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    void fetchModalityCatalog().then((data) => {
      if (!cancelled) {
        setCatalog(data);
        setLoading(false);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  // Rows only exist after the fetch resolves, so the map is simply empty
  // while loading - getStatusBadge's optimistic default (undefined reads as
  // available, no spinner) covers the gap, same posture it always had for an
  // unchecked provider.
  const rows = catalog ? catalog[kind] : [];
  const providerStatus: Record<string, ProviderStatus> = {};
  for (const row of rows) {
    providerStatus[row.provider] = row.available ? "available" : "unavailable";
  }

  return {
    providers: [
      { id: null, name: platformDefault.name, description: platformDefault.description, model: null, isPremium: false, requiresConfig: false },
      ...rows.map(toOption),
    ],
    providerStatus,
    loading,
  };
}
