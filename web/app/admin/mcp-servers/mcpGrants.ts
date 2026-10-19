// Grant shapes and small helpers for the MCP server access console (page.tsx).

import { useEffect } from "react";

// Mirrors McpServerAccessGrantResponse (core/mcpserver dto). Declared here
// rather than imported because api.ts's mcpserver calls are still untyped
// (`toJson` returns Promise<any>, and api.ts exports no grant types today) -
// this page is the contract's only consumer, so the shape is pinned at the
// use site. Fold into api.ts if/when that file's mcpserver section gets
// typed responses like its billing/community-template sections already have.
export interface McpServerGrant {
  id: string;
  name: string;
  enabled: boolean;
  // First few chars of the key, safe to display - the backend only stores
  // the sha256 hash of the rest (McpServerAccessGrant's javadoc).
  apiKeyDisplayPrefix: string;
  exposedAgentIds: string[];
  exposedWorkflowIds: string[];
  exposedKnowledgeCollectionIds: string[];
  createdAt: string;
  lastUsedAt: string | null;
}

// McpServerAccessGrantCreatedResponse - create/rotate only; rawApiKey is the
// single plaintext appearance of the key (see KeyRevealDialog below).
export interface McpServerGrantCreated {
  grant: McpServerGrant;
  rawApiKey: string;
}

// Full replacement payload for PUT /api/mcpserver/grants/{id} - all three
// sets every time (see EditGrantPanel's comment on why omitting any wipes it).
export interface GrantIdsPayload {
  exposedAgentIds: string[];
  exposedWorkflowIds: string[];
  exposedKnowledgeCollectionIds: string[];
}

// The only projection this page reads off /api/agents, /api/workflows and
// /api/knowledge/collections responses - just enough to render CheckboxList.
export interface NamedItem {
  id: string;
  name: string;
}

// useTranslations returns an untyped scoped t() (i18n.tsx is still pre-
// migration, see docs/I18N-MIGRATION-PLAN.md) - this is its real call shape,
// same as next-intl's, so props stay compatible with a later library swap.
export type Translator = (key: string, values?: Record<string, string | number>) => string;

export function idSet(ids: string[] | null | undefined): Set<string> {
  return new Set(ids || []);
}

export function toggleInSet(set: Set<string>, id: string): Set<string> {
  const next = new Set(set);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

// Shared by both dialogs below - Escape closes, same as every other modal
// in this app (WorkflowCanvas's wf-modal-backdrop dialogs close on
// backdrop click but rely on the browser default for Escape; BrandIdentity
// Modal is the one place that already wires an explicit Escape listener -
// following that precedent here since a security-sensitive key-reveal /
// destructive-action dialog is exactly the case where keyboard dismissal
// shouldn't be left to chance).
export function useEscapeToClose(onEscape: () => void, active: boolean) {
  useEffect(() => {
    if (!active) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onEscape();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [active, onEscape]);
}
