import { apiFetch, toJson, jsonBody } from "./core";

/**
 * Edge relay (2026-10-14): this browser as the model behind the tenant's
 * channels. Mirrors core's EdgeRelayController (/api/channels/edge-relay).
 * Every call is tenant-scoped by the bearer token and needs
 * PERM_MANAGE_CHANNELS.
 */
export type RelayChannel = "SMS" | "WHATSAPP";

export interface RelayRegisterResponse {
  relayId: string;
  heartbeatSeconds: number;
  claimWaitSecondsMax: number;
  replyWaitSeconds: number;
}

export interface RelayTurn {
  role: "user" | "assistant";
  text: string;
}

export interface RelayJob {
  jobId: string;
  tenantId: string;
  channel: RelayChannel;
  sessionId: string;
  agentId: string;
  externalIdMasked: string;
  message: string;
  history: RelayTurn[];
  createdAtEpochMs: number;
  status: string;
  claimedBy: string | null;
}

export interface RelayStatus {
  relayId: string;
  subject: string;
  channels: RelayChannel[];
  modelLabel: string | null;
  deviceLabel: string | null;
  registeredAtEpochMs: number;
  answered: number;
}

export interface RelayStatusResponse {
  enabled: boolean;
  relays: RelayStatus[];
}

export const edgeRelayApi = {
  getEdgeRelayStatus: () =>
    apiFetch(`/api/channels/edge-relay/status`, { skipGetCache: true }).then(toJson<RelayStatusResponse>),
  registerEdgeRelay: (channels: RelayChannel[], modelLabel: string | null, deviceLabel: string | null) =>
    apiFetch(`/api/channels/edge-relay/register`, {
      method: "POST",
      ...jsonBody({ channels, modelLabel, deviceLabel }),
    }).then(toJson<RelayRegisterResponse>),
  heartbeatEdgeRelay: async (relayId: string) => {
    const res = await apiFetch(`/api/channels/edge-relay/${encodeURIComponent(relayId)}/heartbeat`, { method: "POST" });
    if (!res.ok) throw Object.assign(new Error(`heartbeat ${res.status}`), { status: res.status });
  },
  unregisterEdgeRelay: async (relayId: string) => {
    await apiFetch(`/api/channels/edge-relay/${encodeURIComponent(relayId)}`, { method: "DELETE" });
  },
  // Long-poll: the server holds the request for up to `wait` seconds and
  // answers 204 when nothing arrived, so the client timeout must exceed it.
  nextEdgeRelayJob: async (relayId: string, waitSeconds: number): Promise<RelayJob | null> => {
    const res = await apiFetch(
      `/api/channels/edge-relay/${encodeURIComponent(relayId)}/next?wait=${Math.max(0, Math.floor(waitSeconds))}`,
      { skipGetCache: true, timeoutMs: (waitSeconds + 15) * 1000 },
    );
    if (res.status === 204) return null;
    if (!res.ok) throw Object.assign(new Error(`next ${res.status}`), { status: res.status });
    return (await res.json()) as RelayJob;
  },
  replyEdgeRelayJob: async (jobId: string, relayId: string, text: string) => {
    const res = await apiFetch(`/api/channels/edge-relay/jobs/${encodeURIComponent(jobId)}/reply`, {
      method: "POST",
      ...jsonBody({ relayId, text }),
    });
    // 410: the server already answered hosted (timeout) - not an error for the loop.
    if (!res.ok && res.status !== 410) throw Object.assign(new Error(`reply ${res.status}`), { status: res.status });
    return res.ok;
  },
  failEdgeRelayJob: async (jobId: string, relayId: string, reason: string) => {
    await apiFetch(`/api/channels/edge-relay/jobs/${encodeURIComponent(jobId)}/fail`, {
      method: "POST",
      ...jsonBody({ relayId, reason }),
    });
  },
};
