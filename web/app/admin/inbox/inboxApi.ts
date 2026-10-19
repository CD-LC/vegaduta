// Operator inbox client seam (live human handoff, backend migration V214).
//
// HARD CROSS-WORKSTREAM DEPENDENCY, stated plainly rather than worked
// around: web/app/lib/api.ts is the single shared API client (its private
// apiFetch/toJson own token attach, 401-retry-once, the global error toast
// bus and client-error beacons) and it is owned by a concurrent workstream -
// this workstream may not edit it, and hand-rolling fetch() here would
// bypass all of that shared handling, which the repo's coordination notes
// explicitly prohibit. So this module DELEGATES: each function looks up the
// named method on the shared `api` object and calls it if the api.ts owner
// has landed it, and rejects with InboxClientNotWiredError otherwise -
// which InboxPanel renders as an honest "console client not wired yet"
// banner, distinct from "load failed" and "empty". The moment the methods
// below land in api.ts, this page goes fully live with no change here.
//
// EXACT METHODS THE api.ts OWNER SHOULD ADD (spec, matching the live
// backend endpoints in core/omnichannel/.../handoff/OperatorInboxController):
//
//   listHandoffInbox: () =>
//     apiFetch("/api/handoff/inbox").then(toJson),
//   getHandoffTranscript: (sessionId) =>
//     apiFetch(`/api/handoff/sessions/${sessionId}/transcript`).then(toJson),
//   escalateHandoffSession: (sessionId, reason) =>
//     apiFetch(`/api/handoff/sessions/${sessionId}/escalate`, { method: "POST", ...jsonBody({ reason }) }).then(toJson),
//   takeOverHandoffSession: (sessionId) =>
//     apiFetch(`/api/handoff/sessions/${sessionId}/take-over`, { method: "POST" }).then(toJson),
//   replyHandoffSession: (sessionId, body) =>
//     apiFetch(`/api/handoff/sessions/${sessionId}/reply`, { method: "POST", ...jsonBody({ body }) }).then(toJson),
//   releaseHandoffSession: (sessionId) =>
//     apiFetch(`/api/handoff/sessions/${sessionId}/release`, { method: "POST" }).then(toJson),
//
// All six return InboxSessionResponse / TranscriptMessageResponse shapes
// (the types below mirror the backend DTO records field-for-field).

import { api } from "../../lib/api";

export type HandoffState = "AGENT" | "AWAITING_HUMAN" | "HUMAN";

export type HandoffChannel =
  | "WHATSAPP"
  | "MESSENGER"
  | "INSTAGRAM"
  | "SLACK"
  | "DISCORD"
  | "X"
  | "TELEGRAM"
  | "EMAIL"
  | "TEAMS"
  | "VOICE"
  | "SMS"
  | "SIGNAL";

/** Mirrors OperatorInboxController.InboxSessionResponse. */
export interface HandoffSessionSummary {
  sessionId: string;
  channel: HandoffChannel;
  externalIdMasked: string;
  agentId: string;
  handoffState: HandoffState;
  escalationReason: string | null;
  escalatedAt: string | null;
  takenOverBy: string | null;
  takenOverAt: string | null;
  lastMessagePreview: string | null;
  lastMessageAt: string;
  createdAt: string;
}

/** Mirrors OperatorInboxController.TranscriptMessageResponse. */
export interface HandoffTranscriptMessage {
  id: string;
  direction: "INBOUND" | "OUTBOUND";
  author: string | null;
  body: string;
  createdAt: string;
}

/** Mirrors OperatorInboxController.ConversationSummary (the paged /conversations row - InboxSessionResponse plus pairing state). */
export interface ConversationSummary {
  sessionId: string;
  channel: HandoffChannel;
  externalIdMasked: string;
  resolvedPhoneMasked: string | null;
  paired: boolean;
  pairingCode: string | null;
  agentId: string;
  handoffState: HandoffState;
  escalationReason: string | null;
  escalatedAt: string | null;
  takenOverBy: string | null;
  takenOverAt: string | null;
  lastMessagePreview: string | null;
  lastMessageAt: string;
  createdAt: string;
}

/** Mirrors OperatorInboxController.ConversationPageResponse. */
export interface ConversationPage {
  items: ConversationSummary[];
  total: number;
  page: number;
  totalPages: number;
}

/** Mirrors OperatorInboxService.InboxStats - the strip above the list. */
export interface InboxStats {
  waitingHuman: number;
  held: number;
  pendingPairing: number;
  agentHandled: number;
  total: number;
}

export type ConversationView = "all" | "waiting" | "held" | "agent" | "pending";

/** Thrown (as a rejection) when the shared api.ts client doesn't carry the handoff methods yet - see this module's header. */
export class InboxClientNotWiredError extends Error {
  constructor(method: string) {
    super(
      `The shared API client (web/app/lib/api.ts) does not have "${method}" yet - ` +
        "the operator-inbox backend is live, but the console client methods are a " +
        "pending cross-workstream follow-up owned by that file's workstream."
    );
    this.name = "InboxClientNotWiredError";
  }
}

function call<T>(method: string, ...args: unknown[]): Promise<T> {
  const fn = (api as unknown as Record<string, unknown>)[method];
  if (typeof fn !== "function") {
    // Rejected promise, never a synchronous throw - callers uniformly use
    // .catch()/await, and a sync throw from a "returns a Promise" function
    // would skip those handlers.
    return Promise.reject(new InboxClientNotWiredError(method));
  }
  return (fn as (...fnArgs: unknown[]) => Promise<T>).apply(api, args);
}

export const inboxApi = {
  list: () => call<HandoffSessionSummary[]>("listHandoffInbox"),
  conversations: (params: { channel?: string; view?: ConversationView; q?: string; page?: number; size?: number }) =>
    call<ConversationPage>("listHandoffConversations", params),
  stats: () => call<InboxStats>("getHandoffInboxStats"),
  // Pairing queue actions - served by ChannelPairingController, already on
  // the shared client under these names.
  approve: (sessionId: string) => call<unknown>("approvePairing", sessionId),
  reject: (sessionId: string) => call<unknown>("rejectPairing", sessionId),
  unpair: (sessionId: string) => call<unknown>("unpairChannelSession", sessionId),
  reassignAgent: (sessionId: string, agentId: string) =>
    call<unknown>("rebindChannelSessionAgent", sessionId, agentId),
  transcript: (sessionId: string) => call<HandoffTranscriptMessage[]>("getHandoffTranscript", sessionId),
  escalate: (sessionId: string, reason: string) =>
    call<HandoffSessionSummary>("escalateHandoffSession", sessionId, reason),
  takeOver: (sessionId: string) => call<HandoffSessionSummary>("takeOverHandoffSession", sessionId),
  reply: (sessionId: string, body: string) => call<HandoffSessionSummary>("replyHandoffSession", sessionId, body),
  release: (sessionId: string) => call<HandoffSessionSummary>("releaseHandoffSession", sessionId),
};
