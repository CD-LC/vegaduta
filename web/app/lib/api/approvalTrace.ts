import { apiFetch, toJsonQuietOn } from "./core";

// What an approved (or refused) request led to: the decision, the conversation it belongs to and the
// agent's tool calls after it (core/agents-tools approvaltrace/ApprovalTraceController). Read quietly on
// 403/404 so a viewer without access to the agent, or an older core, sees "no trace" instead of an error.

export interface ApprovalTraceCall {
  id: string;
  toolName: string;
  startedAt: string;
  completedAt: string;
  outcome: "OK" | "FAILED";
  inputSummary: string | null;
  outputSummary: string | null;
  errorMessage: string | null;
}

export interface ApprovalTrace {
  requestId: string;
  actionType: string;
  status: string;
  decidedBy: string | null;
  decidedAt: string | null;
  conversation: { agentId: string; sessionId: string; goal: string | null } | null;
  windowEnd: string | null;
  callsTruncated: boolean;
  calls: ApprovalTraceCall[];
  traceNote: string;
}

export const approvalTraceApi = {
  getApprovalTrace: (id: string) =>
    apiFetch(`/api/approval-traces/${encodeURIComponent(id)}`, { skipGetCache: true }).then(
      toJsonQuietOn<ApprovalTrace>([403, 404])
    ),
};
