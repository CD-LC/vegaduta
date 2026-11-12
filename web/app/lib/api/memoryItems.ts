import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { MemoryItem, MemoryItemDetail } from "../../memory/memoryItemTypes";

// The unified-memory review surface (core MemoryItemController +
// MemoryItemExportController, ADR-0028 WP6). Reads answer 404 while the
// unified-memory flags (platform AND tenant AND agent) are off, so the reads
// are quiet on 404 and the panel renders the feature-off empty state instead
// of an error. Decisions (approve/reject/revert/erase) are deliberately NOT
// quiet and NOT gated: a person must always be able to review and erase.
// Edit and pin ARE gated server-side (404 while the feature is dark): they are
// only offered from rows the list already returned, so a 404 here means the
// flag flipped under the page and is surfaced like any other failure.
const base = (agentId: string) => `/api/agents/${encodeURIComponent(agentId)}/memory-items`;

export const memoryItemsApi = {
  // Newest first. An EDITOR gets every status when no `status` is passed; a
  // VIEWER only ever gets ACTIVE rows. null = the flag is off for this agent.
  listMemoryItems: (agentId: string, opts?: { status?: string; kind?: string; limit?: number }) => {
    const params = new URLSearchParams();
    if (opts?.status) params.set("status", opts.status);
    if (opts?.kind) params.set("kind", opts.kind);
    params.set("limit", String(opts?.limit ?? 500));
    return apiFetch(`${base(agentId)}?${params}`).then(toJsonQuietOn<MemoryItem[] | null>([404]));
  },
  // Lineage and event history are populated for EDITOR only. null = gone or flagged off.
  getMemoryItem: (agentId: string, itemId: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}`).then(toJsonQuietOn<MemoryItemDetail | null>([404])),
  // PENDING -> TRUSTED + ACTIVE; 204 on success, 409 when the state already moved.
  approveMemoryItem: (agentId: string, itemId: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}/approve`, { method: "POST" }).then(toJson<void>),
  // PENDING -> REJECTED.
  rejectMemoryItem: (agentId: string, itemId: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}/reject`, { method: "POST" }).then(toJson<void>),
  // ACTIVE -> REVERTED: recall stops returning it at once.
  revertMemoryItem: (agentId: string, itemId: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}/revert`, { method: "POST" }).then(toJson<void>),
  // Edit = a NEW PENDING version (content re-screened server-side), never an
  // in-place change; resolves to the new version. 409 when the item is no longer
  // PENDING/ACTIVE, 422 when the text fails the content screen.
  editMemoryItem: (agentId: string, itemId: string, content: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}`, {
      method: "PATCH",
      ...jsonBody({ content }),
    }).then(toJson<MemoryItem>),
  // Pin an ACTIVE item so consolidation never forgets it (a person only); 204.
  pinMemoryItem: (agentId: string, itemId: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}/pin`, { method: "POST" }).then(toJson<void>),
  // Clear a pin; 204.
  unpinMemoryItem: (agentId: string, itemId: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}/pin`, { method: "DELETE" }).then(toJson<void>),
  // Erase with the lineage cascade: the item, everything derived from it, and
  // the lineage/event history go; one ERASED tombstone per erased id stays.
  eraseMemoryItem: (agentId: string, itemId: string) =>
    apiFetch(`${base(agentId)}/${encodeURIComponent(itemId)}`, { method: "DELETE" }).then(toJson<void>),
  // The signed-in user's own cross-agent export (subject_keys match the
  // principal). Fetched with the bearer token; skipGetCache so the file is
  // never a replayed earlier response. null = the platform flag is off.
  exportMemoryItems: async (): Promise<{ blob: Blob; filename: string } | null> => {
    const res = await apiFetch(`/api/memory-items/export`, { skipGetCache: true });
    if (!res.ok) {
      await toJsonQuietOn([404])(res);
      return null;
    }
    return { blob: await res.blob(), filename: "memory-items-export.json" };
  },
};
