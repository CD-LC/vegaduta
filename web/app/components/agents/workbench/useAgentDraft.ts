"use client";

import { useEffect, useRef, useState } from "react";
import { AGENT_TEMPLATES } from "../../agentTemplates";
import { consumeTryDraftForWorkbench } from "../../try/draftHandoff";
import { findStarterTemplate, type StarterTemplate } from "../starterTemplates";
import { useAgentDraftState } from "./state/useAgentDraftState";
import type { WorkbenchProps } from "./types";

export function useAgentDraft(p: WorkbenchProps) {
  const { providers, selectedAgentId, initialTemplateKey } = p;

  // ---- Draft (new agent) form state ------------------------------------
  // One reducer (state/agentDraftReducer.ts) owns the creation form: fields,
  // the provider-scoped model picker (draftModel*), the save status
  // (busy/error/success -> draftStatus clean|dirty|saving|error). The names
  // below are the setters the handlers always used.
  const {
    name,
    setName,
    systemPrompt,
    setSystemPrompt,
    providerName,
    setProviderName,
    model,
    setModel,
    draftModelCatalog,
    setDraftModelCatalog,
    draftModelLoading,
    setDraftModelLoading,
    draftModelUseCustom,
    setDraftModelUseCustom,
    draftModelCatalogError,
    setDraftModelCatalogError,
    voiceModelProvider,
    setVoiceModelProvider,
    voiceModel,
    setVoiceModel,
    videoModelProvider,
    setVideoModelProvider,
    videoModel,
    setVideoModel,
    ttsProvider,
    setTtsProvider,
    ttsModel,
    setTtsModel,
    knowledgeBaseEnabled,
    setKnowledgeBaseEnabled,
    maxTokens,
    setMaxTokens,
    temperature,
    setTemperature,
    topP,
    setTopP,
    newAgentMode,
    setNewAgentMode,
    newAgentTuningMode,
    setNewAgentTuningMode,
    activeTemplateKey,
    setActiveTemplateKey,
    busy,
    setBusy,
    error,
    setError,
    success,
    setSuccess,
    draftStatus,
    resetDraft,
  } = useAgentDraftState(providers[0]?.providerName);
  const nameInputRef = useRef(null);

  const [switcherOpen, setSwitcherOpen] = useState(false);

  // So Escape-to-close on the switcher listbox can return focus to the
  // trigger instead of dropping it.
  const switcherTriggerRef = useRef(null);

  // WB-003: identifies "the draft/selection this in-flight handleCreate
  // request was for" - bumped whenever the user starts a new draft or
  // switches to an existing agent while a create is still in flight, so a
  // slow create() response can't clobber whatever the user has since
  // navigated to with onSelect(created.id).
  const draftRequestIdRef = useRef(0);

  // WB-012: mirrors selectedAgentId in a ref so async handlers can check,
  // after their await resolves, whether the user has since switched agents
  // - state (selectedAgentId) can't be read fresh inside an already-queued
  // closure, but a ref can.
  const selectedAgentIdRef = useRef(selectedAgentId);

  useEffect(() => {
    selectedAgentIdRef.current = selectedAgentId;
  }, [selectedAgentId]);

  // Named applyTemplate, not useTemplate - a "use"-prefixed identifier
  // makes eslint-plugin-react-hooks treat this as a Hook, which then
  // flags the call site below (inside a useEffect) as a rules-of-hooks
  // violation even though it's just a plain function.
  function applyTemplate(template: (typeof AGENT_TEMPLATES)[number]) {
    setSystemPrompt(template.systemPrompt);
    setKnowledgeBaseEnabled(!!template.suggestKnowledgeBase);
    setActiveTemplateKey(template.key);
  }

  // Starter equivalent: also pre-fills the name (the sandbox's default agent
  // name for that persona) when the draft has none, so a FREE tenant's first
  // agent is literally "pick, Save draft" - the whole point of the allow-list.
  // The name stays editable; a name the person already typed is never
  // overwritten.
  function applyStarterTemplate(starter: StarterTemplate) {
    setSystemPrompt(starter.systemPrompt);
    setKnowledgeBaseEnabled(false);
    setActiveTemplateKey(starter.key);
    setName((current) => (current.trim() ? current : starter.defaultName));
  }

  useEffect(() => {
    if (!initialTemplateKey) return;
    // "starter:<persona>" keys come from the allow-list, everything else
    // from the full gallery - the prefix check keeps the two apart.
    const starter = findStarterTemplate(initialTemplateKey);
    if (starter) {
      applyStarterTemplate(starter);
      return;
    }
    const template = AGENT_TEMPLATES.find((t) => t.key === initialTemplateKey);
    if (template) applyTemplate(template);
  }, [initialTemplateKey]);

  // ---- /try HANDOFF READER (2026-09-19) - begin ---------------------------
  // A visitor who built an agent on /try WITHOUT a cloud sandbox (on-device
  // run, or sandbox unreachable) has no demo token for core to copy from.
  // components/try/draftHandoff.ts keeps their draft in this browser's
  // localStorage - never a URL, size-capped, expiring, re-validated on read -
  // and this effect applies it ONCE to the new-agent draft. Read-and-remove,
  // so it can never re-apply over something typed later. An explicit
  // ?template= deep link or an already-selected agent wins and leaves the
  // record untouched. Same "never overwrite a typed name" rule as
  // applyStarterTemplate. Everything lands in the visible, editable draft:
  // nothing is saved until the person presses Save.
  useEffect(() => {
    if (initialTemplateKey || selectedAgentId) return;
    const handoff = consumeTryDraftForWorkbench();
    if (!handoff) return;
    if (handoff.systemPrompt) {
      setSystemPrompt(handoff.systemPrompt);
      setKnowledgeBaseEnabled(false);
      setActiveTemplateKey(handoff.templateKey);
    } else if (handoff.aboutParagraph) {
      setSystemPrompt((current) => `${current}\n\n${handoff.aboutParagraph}`);
    }
    if (handoff.name) setName((current) => (current.trim() ? current : handoff.name));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // New-agent default: the provider/model this workspace can actually use right now (server-picked from the
  // keys' recent call history) instead of the first row of the provider list - which on prod was a Vertex key
  // the provider refused, so every first chat answered "rejected our credentials". Applied once per fresh
  // draft and only while the person has not already chosen a provider or model themselves.
  const draftDefaultAppliedRef = useRef(false);

  // The server's own caveat about that default (needsAttention + message), shown under the picker.
  const [defaultModelNotice, setDefaultModelNotice] = useState<{
    providerName: string | null;
    needsAttention: boolean;
    message: string | null;
  } | null>(null);

  const draftTouchedRef = useRef(false);

  const [draftEpoch, setDraftEpoch] = useState(0);

  return {
    draftStatus,
    resetDraft,
    setError,
    setSuccess,
    name,
    systemPrompt,
    draftDefaultAppliedRef,
    draftTouchedRef,
    setDraftEpoch,
    draftRequestIdRef,
    setName,
    setSystemPrompt,
    setProviderName,
    setModel,
    setDraftModelCatalog,
    setDraftModelUseCustom,
    setVoiceModelProvider,
    setVoiceModel,
    setVideoModelProvider,
    setVideoModel,
    setKnowledgeBaseEnabled,
    setMaxTokens,
    setTemperature,
    setTopP,
    setNewAgentMode,
    setNewAgentTuningMode,
    setActiveTemplateKey,
    setSwitcherOpen,
    nameInputRef,
    busy,
    setBusy,
    providerName,
    model,
    maxTokens,
    temperature,
    topP,
    newAgentMode,
    knowledgeBaseEnabled,
    newAgentTuningMode,
    voiceModelProvider,
    voiceModel,
    videoModelProvider,
    videoModel,
    ttsProvider,
    ttsModel,
    activeTemplateKey,
    selectedAgentIdRef,
    setDefaultModelNotice,
    draftEpoch,
    setDraftModelLoading,
    setDraftModelCatalogError,
    switcherTriggerRef,
    switcherOpen,
    error,
    success,
    applyStarterTemplate,
    applyTemplate,
    defaultModelNotice,
    draftModelUseCustom,
    draftModelCatalog,
    draftModelLoading,
    draftModelCatalogError,
    setTtsProvider,
    setTtsModel,
  };
}
