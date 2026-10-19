import { apiFetch, toJson, toJsonQuietOn } from "./core";
import {
  exportFallbackName,
  filenameFromContentDisposition,
  type ChatImportFeatures,
  type ChatImportPreview,
  type ChatImportResult,
  type ExportFormat,
  type ImportedChatSummary,
  type ImportedChatView,
} from "../chatPortability";
import type { ImportPreview } from "../../memory/memoryImport";

// Chat portability (core ChatImportController / ChatExportController): import conversations from a
// ChatGPT / Claude data export, and download a chat as Markdown / JSON / text. Both halves are behind
// server flags (app.chat-import.enabled / app.chat-export.enabled, default off): every route except the
// features probe answers 404 while off, which these calls treat as "feature unavailable", not an error.

type Download = { blob: Blob; filename: string };

// A download is fetched with the bearer token (a plain link would 401); skipGetCache so the file is
// never a replayed earlier response. A non-OK answer goes through toJson so the usual friendly error
// (and its toast) shows, exactly like every other API call.
async function downloadOf(path: string, fallback: string): Promise<Download> {
  const res = await apiFetch(path, { skipGetCache: true });
  if (!res.ok) await toJson(res);
  return { blob: await res.blob(), filename: filenameFromContentDisposition(res.headers.get("Content-Disposition"), fallback) };
}

export const chatPortabilityApi = {
  // { importEnabled, exportEnabled, maxBytes, maxImportConversations }. Quiet on the statuses where the
  // control is simply not available (signed out, older core without the route, core down).
  getChatPortabilityFeatures: () => apiFetch("/api/chat-import/features").then(toJsonQuietOn<ChatImportFeatures>([401, 403, 404, 500, 502, 503])),

  // Reads the uploaded .zip / conversations.json and lists what is in it. Nothing is stored.
  previewChatImport: (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return apiFetch("/api/chat-import/preview", { method: "POST", body: form, timeoutMs: 180000 }).then(toJsonQuietOn<ChatImportPreview>([404]));
  },

  // Stores the chosen conversations (ids are the keys the preview returned) as read-only imported chats.
  // The file is sent again: the server keeps nothing between the preview and this call.
  importChats: (file: File, ids: string[]) => {
    const form = new FormData();
    form.append("file", file);
    for (const id of ids) form.append("ids", id);
    return apiFetch("/api/chat-import", { method: "POST", body: form, timeoutMs: 180000 }).then(toJsonQuietOn<ChatImportResult>([404]));
  },

  listImportedChats: () => apiFetch("/api/chat-import/chats").then(toJsonQuietOn<ImportedChatSummary[]>([404])),
  getImportedChat: (id: string) => apiFetch(`/api/chat-import/chats/${encodeURIComponent(id)}`).then(toJsonQuietOn<ImportedChatView>([404])),
  deleteImportedChat: (id: string) =>
    apiFetch(`/api/chat-import/chats/${encodeURIComponent(id)}`, { method: "DELETE" }).then(toJsonQuietOn([404])),

  // Opt-in "suggest memories": proposes first-person lines from the chosen imported chats as a
  // memory-import preview ({ facts, stats, capacity, source }). Saves nothing - the user then saves
  // the lines they keep through importMemory (/api/memory/import).
  getChatMemoryCandidates: (chatIds: string[]) =>
    apiFetch("/api/chat-import/memory-candidates", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ chatIds }),
    }).then(toJsonQuietOn<ImportPreview>([404])),

  exportImportedChat: (id: string, format: ExportFormat): Promise<Download> =>
    downloadOf(`/api/chat-import/chats/${encodeURIComponent(id)}/export?format=${format}`, exportFallbackName(format, "imported-chat")),
  exportAllImportedChats: (format: ExportFormat): Promise<Download> =>
    downloadOf(`/api/chat-import/export?format=${format}`, exportFallbackName(format, "imported-chats")),
  // A live conversation. 404 = switched off, the session expired, or no access to this agent.
  exportChatSession: (agentId: string, sessionId: string, format: ExportFormat): Promise<Download> =>
    downloadOf(
      `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/export?format=${format}`,
      exportFallbackName(format, "chat")
    ),
};
