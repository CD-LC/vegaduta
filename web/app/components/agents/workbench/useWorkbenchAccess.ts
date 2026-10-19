"use client";

import { useCallback, useEffect, useState } from "react";
import { codeIntel } from "../../../code-intel/lib";
import { api } from "../../../lib/api";
import type { ResolvedTool } from "../AgentWorkbench/types";
import type { WorkbenchProps } from "./types";
import type { useAgentDraft } from "./useAgentDraft";
import type { useWorkbenchBase } from "./useWorkbenchBase";
import type { useWorkbenchTenant } from "./useWorkbenchTenant";

export function useWorkbenchAccess(
  _p: WorkbenchProps,
  prev: ReturnType<typeof useWorkbenchBase> & ReturnType<typeof useWorkbenchTenant> & ReturnType<typeof useAgentDraft>
) {
  const { selectedAgent } = prev;

  // ---- Existing-agent live-editable state -------------------------------
  // The three PATCHable generation params, mirroring the slider trio below -
  // null until an existing agent is selected (the selectedAgentId effect
  // hydrates it from the agent's saved values).
  const [genDraft, setGenDraft] = useState<{ maxTokens: number; temperature: number; topP: number } | null>(null);

  const [genBusy, setGenBusy] = useState(false);

  const [toolsResolved, setToolsResolved] = useState<undefined | "loading" | "error" | ResolvedTool[]>(undefined);

  const [toolBusyName, setToolBusyName] = useState(null);

  const [editingKnowledge, setEditingKnowledge] = useState(false);

  const [knowledgeDraft, setKnowledgeDraft] = useState(new Set<string>());

  const [knowledgeBusy, setKnowledgeBusy] = useState(false);

  const [modeBusy, setModeBusy] = useState(false);

  // Code Intelligence card (AgentController's PATCH /{agentId}/code-workspace
  // + /{agentId}/code-intel-repo-access, migration V103).
  // codeIntelToolsAvailable is a REAL live signal, not a guess: GET
  // /api/agents/{id}/available-tools only ever resolves search_code
  // (AgentToolProvider#resolveCodeIntelTools) once an operator has
  // code-intel.enabled=true, so its presence/absence in that already-
  // existing endpoint's response IS "is code intelligence reachable on this
  // platform" - no new health check invented. Fetched once (not on every
  // agent switch), since it reflects a platform-wide flag, not anything
  // agent-specific.
  const [codeIntelToolsAvailable, setCodeIntelToolsAvailable] = useState(undefined);
  // undefined|"loading"|"error"|true|false
  const [editingCodeIntelRepos, setEditingCodeIntelRepos] = useState(false);

  const [codeIntelRepoDraft, setCodeIntelRepoDraft] = useState(new Set<string>());

  const [codeIntelReposBusy, setCodeIntelReposBusy] = useState(false);

  // The per-provider opt-in toggles (code-workspace, e2b, sarvam, xai-server-
  // tools) and their busy states were replaced by the generic Capabilities
  // section - see AgentCapabilities.tsx + /api/agents/{id}/capabilities.
  // Split out so the retry link below can re-invoke the same fetch without
  // the effect's "already resolved" guard blocking it - a bare "false" used
  // to mean both "platform genuinely has no code-intel" and "the fetch blew
  // up", which is why the "error" state below exists as a distinct outcome.
  const fetchCodeIntelTools = useCallback(() => {
    if (!selectedAgent) return;
    setCodeIntelToolsAvailable("loading");
    api
      .listAvailableTools(selectedAgent.id)
      .then((tools) =>
        setCodeIntelToolsAvailable(Array.isArray(tools) && tools.some((tool) => tool.name === "search_code"))
      )
      .catch(() => setCodeIntelToolsAvailable("error"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedAgent?.id]);

  useEffect(() => {
    if (!selectedAgent || codeIntelToolsAvailable !== undefined) return;
    fetchCodeIntelTools();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedAgent?.id]);

  // Repos this tenant has registered with the standalone code-intel
  // microservice - same codeIntel.listRepos() the /code-intel page itself
  // uses (see that page's lib.ts for why this talks to code-intel directly
  // instead of through core's apiFetch) - so the picker below shows real
  // registered repo ids, not a blind text field.
  const [codeIntelRepos, setCodeIntelRepos] = useState(undefined);
  // undefined|"loading"|"error"|array
  useEffect(() => {
    if (codeIntelToolsAvailable !== true) return; // not enabled: nothing to list (SE-11)
    setCodeIntelRepos("loading");
    codeIntel
      .listRepos()
      .then((list) => setCodeIntelRepos(list || []))
      .catch(() => setCodeIntelRepos("error"));
  }, [codeIntelToolsAvailable]);

  return {
    setEditingKnowledge,
    setEditingCodeIntelRepos,
    setGenDraft,
    setKnowledgeDraft,
    setCodeIntelRepoDraft,
    setToolsResolved,
    toolsResolved,
    genDraft,
    genBusy,
    setGenBusy,
    modeBusy,
    setModeBusy,
    toolBusyName,
    setToolBusyName,
    knowledgeBusy,
    setKnowledgeBusy,
    knowledgeDraft,
    codeIntelReposBusy,
    setCodeIntelReposBusy,
    codeIntelRepoDraft,
    editingKnowledge,
    codeIntelToolsAvailable,
    fetchCodeIntelTools,
    editingCodeIntelRepos,
    codeIntelRepos,
  };
}
