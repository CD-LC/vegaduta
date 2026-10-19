import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type { BranchCapabilities, BranchPath, BranchSiblings } from "../chatBranching";

// Chat branching (core com.agenticai.core.agents.branching). Off by default on the server
// (app.chat.branching.enabled): while off every route except the capabilities call answers 404,
// so the chat panel reads getChatBranchingCapabilities() first and never calls the rest.
//
// Edit and regenerate stream exactly like a normal chat turn, so they are not methods here: the
// panel sends them through api.streamChat with the path from branchEditPath / branchRegeneratePath.

const enc = encodeURIComponent;

export function branchMessagesPath(agentId: string, sessionId: string): string {
  return `/api/agents/${enc(agentId)}/sessions/${enc(sessionId)}/messages`;
}

export function branchEditPath(agentId: string, sessionId: string, messageId: string): string {
  return `${branchMessagesPath(agentId, sessionId)}/${enc(messageId)}/edit`;
}

export function branchRegeneratePath(agentId: string, sessionId: string, messageId: string): string {
  return `${branchMessagesPath(agentId, sessionId)}/${enc(messageId)}/regenerate`;
}

export const chatBranchingApi = {
  // 403/404 = a core that predates the feature or an agent the caller cannot run: expected, no toast.
  getChatBranchingCapabilities: (agentId: string): Promise<BranchCapabilities> =>
    apiFetch(`/api/agents/${enc(agentId)}/chat-branching/capabilities`).then(toJsonQuietOn([403, 404])),

  // The path currently shown and replayed to the model, with each message's version position.
  getBranchPath: (agentId: string, sessionId: string): Promise<BranchPath> =>
    apiFetch(`${branchMessagesPath(agentId, sessionId)}?branch=active`).then(toJsonQuietOn([404])),

  getBranchSiblings: (agentId: string, sessionId: string, messageId: string): Promise<BranchSiblings> =>
    apiFetch(`${branchMessagesPath(agentId, sessionId)}/${enc(messageId)}/siblings`).then(toJsonQuietOn([404])),

  // Show another version; resolves to the new active path.
  setBranchActiveLeaf: (agentId: string, sessionId: string, messageId: string): Promise<BranchPath> =>
    apiFetch(`${branchMessagesPath(agentId, sessionId)}/active-leaf`, {
      method: "PUT",
      ...jsonBody({ messageId }),
    }).then(toJson<BranchPath>),
};
