"use client";

import { useEffect } from "react";
import { useTranslations } from "../../../lib/i18n";
import { useCreateBlocker } from "../guide/useCreateBlocker";
import { useModalityCatalogData } from "../useModalityCatalog";

import type { WorkbenchProps } from "./types";

export function useWorkbenchBase(p: WorkbenchProps) {
  const {
    agents,
    providers,
    collections,
    selectedAgentId,
    onSelect,
    onChanged,
    initialTemplateKey,
    initialLabOpen,
    startInDraft,
    agentsLoaded,
  } = p;

  const t = useTranslations("agentWorkbench");

  // 2026-09-19: turns validate()'s failure into plain words + a scroll to the
  // field. validate() below stays the authority on WHETHER a save is blocked.
  const explainCreateBlocker = useCreateBlocker();

  const tTemplates = useTranslations("agentTemplates");

  // Starter templates borrow the sandbox's own persona labels (try_persona*)
  // and carry their UI strings in the same namespace - one label, one
  // destination, whether someone meets "Kirana store" on /try or here.
  const tTry = useTranslations("try");

  const activeProviders = providers.filter((p) => p.status === "ACTIVE");

  // Modality provider options + availability now come from the server
  // catalog (GET /api/providers/modalities) inside the selectors themselves
  // - see useModalityCatalog. This replaced the per-brand probes that used
  // to live here (getSarvamCredential, getElevenLabsCredential, an
  // OPENAI-row scan of `providers`, and a sidecar health read): the
  // workbench only needs the catalog itself for the provider->default-model
  // backfills in the create/save paths below.
  const modalityCatalog = useModalityCatalogData();

  const selectedAgent = agents.find((a) => a.id === selectedAgentId) || null;

  const isDraft = !selectedAgent;

  // Land on the most recently created agent instead of an empty draft when
  // agents already exist and the page hasn't been given a selection yet -
  // matches the mockup always showing a concrete agent in its breadcrumb.
  useEffect(() => {
    if (!selectedAgentId && agents.length > 0 && !startInDraft) {
      onSelect(agents[agents.length - 1].id);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agents.length]);

  return {
    activeProviders,
    selectedAgent,
    isDraft,
    t,
    explainCreateBlocker,
    modalityCatalog,
    tTemplates,
    tTry,
    providers,
    agents,
    selectedAgentId,
    startInDraft,
    onSelect,
    initialTemplateKey,
    onChanged,
    collections,
    agentsLoaded,
    initialLabOpen,
  };
}
