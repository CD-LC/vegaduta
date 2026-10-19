"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { assistantApi } from "../../lib/api/assistant";
import type { AgentResponse } from "../../lib/api/types";
import { initialAgentId, persistAgentSelection, resolveAgentId } from "./agentSelection";

export interface ActiveAgent {
  id: string;
  name: string;
  providerName?: string | null;
  model?: string | null;
  /** True for the person's own Assistant agent (the default; gets the app-context block and the slash features). */
  isAssistant: boolean;
}

/**
 * Which agent the box talks to: the person's Assistant by default, or any other agent of the
 * workspace picked in the composer, deep-linked with ?agent= or remembered from last time
 * (agentSelection.ts). The list is read once the Assistant exists; until then the Assistant is the
 * only option, so the surface works the same on a workspace with one agent as on one with thirty.
 */
export function useActiveAgent(
  assistant: AgentResponse | null,
  queryAgentId: string | null
): {
  agents: AgentResponse[];
  active: ActiveAgent | null;
  select: (id: string) => void;
} {
  const [agents, setAgents] = useState<AgentResponse[]>([]);
  const [selectedId, setSelectedId] = useState(() => initialAgentId(queryAgentId));

  useEffect(() => {
    if (!assistant) return undefined;
    let live = true;
    assistantApi
      .listAgents()
      .then((rows) => {
        if (!live) return;
        const list = Array.isArray(rows) ? rows : [];
        setAgents(list.some((a) => a.id === assistant.id) ? list : [assistant, ...list]);
      })
      .catch(() => {
        if (live) setAgents([assistant]);
      });
    return () => {
      live = false;
    };
  }, [assistant]);

  // The URL SEEDS the selection and a link to a different agent re-seeds it, but it must not
  // outvote a pick made in the box: the pick is written back to ?agent=, so a URL that always won
  // pinned the first agent for good and the picker could never change it.
  const lastQueryRef = useRef(queryAgentId);
  useEffect(() => {
    if (queryAgentId && queryAgentId !== lastQueryRef.current) setSelectedId(queryAgentId);
    lastQueryRef.current = queryAgentId;
  }, [queryAgentId]);

  // Resolve against the known list: the current pick, then the Assistant. An unknown id (deleted
  // agent, another workspace's link) falls back rather than sticking.
  const resolvedId = useMemo(
    () => (agents.length > 0 ? resolveAgentId(agents, null, selectedId, assistant?.id) : assistant?.id ?? ""),
    [agents, selectedId, assistant?.id]
  );

  useEffect(() => {
    if (!assistant || !resolvedId) return;
    // Only a non-default pick is worth an address-bar entry; the Assistant is what a bare URL means.
    persistAgentSelection(resolvedId === assistant.id ? "" : resolvedId);
  }, [resolvedId, assistant]);

  const select = useCallback((id: string) => setSelectedId(id), []);

  const active = useMemo<ActiveAgent | null>(() => {
    if (!assistant) return null;
    const row = agents.find((a) => a.id === resolvedId) ?? assistant;
    return {
      id: row.id,
      name: row.name,
      providerName: row.providerName ?? null,
      model: row.model ?? null,
      isAssistant: row.id === assistant.id,
    };
  }, [assistant, agents, resolvedId]);

  return { agents, active, select };
}
