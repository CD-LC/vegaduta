import { apiFetch, toJson, jsonBody } from "./core";

export const handoffApi = {
  // Operator inbox - live human handoff (OperatorInboxController,
  // core/omnichannel, migration V214). web/app/admin/inbox/inboxApi.ts
  // DELEGATES to these by name at runtime (it rendered an explicit
  // "console client not wired yet" banner until they landed here), so the
  // six method names below are a cross-module contract - rename them in
  // both places or not at all. Plain toJson on all six per that module's
  // spec: any failure here is a real failure worth the global toast.
  listHandoffInbox: () => apiFetch(`/api/handoff/inbox`).then(toJson),
  getHandoffTranscript: (sessionId: string) => apiFetch(`/api/handoff/sessions/${sessionId}/transcript`).then(toJson),
  escalateHandoffSession: (sessionId: string, reason: string) =>
    apiFetch(`/api/handoff/sessions/${sessionId}/escalate`, { method: "POST", ...jsonBody({ reason }) }).then(toJson),
  takeOverHandoffSession: (sessionId: string) =>
    apiFetch(`/api/handoff/sessions/${sessionId}/take-over`, { method: "POST" }).then(toJson),
  replyHandoffSession: (sessionId: string, body: unknown) =>
    apiFetch(`/api/handoff/sessions/${sessionId}/reply`, { method: "POST", ...jsonBody({ body }) }).then(toJson),
  releaseHandoffSession: (sessionId: string) =>
    apiFetch(`/api/handoff/sessions/${sessionId}/release`, { method: "POST" }).then(toJson),
  // Full conversations list + stats strip (2026-09-23 inbox rebuild) -
  // GET /api/handoff/conversations?channel&view&q&page&size and
  // /api/handoff/stats on the same controller. view ∈
  // all|waiting|held|agent|pending; empty params are simply omitted.
  listHandoffConversations: (params?: { channel?: string; view?: string; q?: string; page?: number; size?: number }) => {
    const qs = new URLSearchParams();
    if (params?.channel) qs.set("channel", params.channel);
    if (params?.view && params.view !== "all") qs.set("view", params.view);
    if (params?.q) qs.set("q", params.q);
    if (params?.page) qs.set("page", String(params.page));
    if (params?.size) qs.set("size", String(params.size));
    const suffix = qs.size ? `?${qs.toString()}` : "";
    return apiFetch(`/api/handoff/conversations${suffix}`).then(toJson);
  },
  getHandoffInboxStats: () => apiFetch(`/api/handoff/stats`).then(toJson),
};
