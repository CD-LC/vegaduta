import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { ForgetAllResult, MemoryFactSource, MemoryOverview } from "../../memory/memoryTypes";
import type { ImportPreview, ImportRequestFact, ImportResult } from "../../memory/memoryImport";

// User-visible, editable memory (core UserMemoryController, /api/memory).
// Scoped server-side to the signed-in user; labels are persona | user_profile |
// project_notes | project:<slug> | active_project (a pointer to one project slug).
export const memoryApi = {
  getMemory: () => apiFetch("/api/memory").then(toJson<MemoryOverview>),
  // Same read for surfaces where memory is a nicety (the chat project switcher):
  // a missing or failing endpoint must not raise the global error toast there.
  getMemoryQuiet: () => apiFetch("/api/memory").then(toJsonQuietOn<MemoryOverview | null>([403, 404, 500, 502, 503])),
  saveMemoryBlock: (label: string, content: string) =>
    apiFetch(`/api/memory/blocks/${encodeURIComponent(label)}`, {
      method: "PUT",
      ...jsonBody({ content }),
    }).then(toJson),
  deleteMemoryBlock: (label: string) =>
    apiFetch(`/api/memory/blocks/${encodeURIComponent(label)}`, { method: "DELETE" }).then(toJson),
  editMemoryFact: (scope: string, id: string, content: string) =>
    apiFetch(`/api/memory/facts/${encodeURIComponent(scope)}/${encodeURIComponent(id)}`, {
      method: "PATCH",
      ...jsonBody({ content }),
    }).then(toJson),
  deleteMemoryFact: (scope: string, id: string) =>
    apiFetch(`/api/memory/facts/${encodeURIComponent(scope)}/${encodeURIComponent(id)}`, { method: "DELETE" }).then(toJson),
  // Add a fact by hand. scope "agent" needs agentId; "shared" ignores it.
  addMemoryFact: (content: string, scope: "agent" | "shared", agentId?: string | null) =>
    apiFetch(`/api/memory/facts`, {
      method: "POST",
      ...jsonBody(scope === "agent" ? { content, scope, agentId } : { content, scope }),
    }).then(toJson),
  // Erase every fact and note the signed-in user has. Resolves to
  // { agentFacts, sharedAndBlocks, graphEntities, graphErased }.
  // Quiet on 404: the self-service flag was switched off after the page loaded;
  // the card then hides itself instead of raising an error toast.
  forgetAllMemory: () => apiFetch(`/api/memory?confirm=true`, { method: "DELETE" }).then(toJsonQuietOn<ForgetAllResult>([404])),
  // Download of everything stored. Fetched with the bearer token (a plain link
  // would 401); skipGetCache so the file is never a replayed earlier response.
  exportMemory: async (): Promise<{ blob: Blob; filename: string }> => {
    const res = await apiFetch(`/api/memory/export`, { skipGetCache: true });
    if (!res.ok) await toJsonQuietOn([404])(res);
    return { blob: await res.blob(), filename: "memory-export.json" };
  },
  // Which conversation taught a fact. Quiet on 404 (fact gone, or a core
  // without the endpoint): the row then just says the origin is unknown.
  getMemoryFactSource: (scope: string, id: string) =>
    apiFetch(`/api/memory/facts/${encodeURIComponent(scope)}/${encodeURIComponent(id)}/source`).then(toJsonQuietOn<MemoryFactSource | null>([404])),
  // "Import from another assistant" (core MemoryImportController). Resolves to
  // { maxFacts, maxChars }; 404 = the feature is switched off, so the page hides the
  // card. Quiet on the usual "nicety is unavailable" statuses, like getMemoryQuiet.
  getMemoryImportLimits: () =>
    apiFetch("/api/memory/import/limits").then(toJsonQuietOn<{ maxFacts: number; maxChars: number }>([401, 403, 404, 500, 502, 503])),
  // Parse + screen a pasted answer. No side effects. 404 is quiet (feature switched off).
  previewMemoryImport: (text: string, sourceHint: string) =>
    apiFetch(`/api/memory/import/preview`, { method: "POST", ...jsonBody({ text, sourceHint }) }).then(toJsonQuietOn<ImportPreview>([404])),
  // Save the selected facts through the same limits as add-a-fact. Resolves to
  // { saved, skipped: [{ text, reason }], source }.
  importMemory: (facts: ImportRequestFact[], source: string) =>
    apiFetch(`/api/memory/import`, { method: "POST", ...jsonBody({ facts, source }) }).then(toJsonQuietOn<ImportResult>([404])),
};
