import { apiFetch, jsonBody, toJson, toJsonQuietOn, type AgentStepEvent } from "./core";
import { chatApi } from "./chat";
import type { ChatResumeLifecycle } from "./chatResume";
import { agentLifecycleApi } from "./agentLifecycle";
import { governanceApi } from "./governance";
import { researchApi } from "./research";
import { scheduledTasksApi } from "./scheduledTasks";
import { memoryApi } from "./memory";
import { agentsApi } from "./agents";
import { providersApi } from "./providers";
import type { AgentJobSummaryResponse, ApprovalRequestResponse, ChannelsStatusResponse } from "./types";
import type { ChatTranscript } from "../../components/assistant/threadIndex";
import type { ScheduledTask } from "../scheduledTasks";

// The /assistant surface's view of the API (docs/architecture/PERSONAL-ASSISTANT-SURFACE-2026-10-17.md,
// Phase 1): thin wrappers over the existing modules, named for what the surface does with them. No new
// endpoint; the one call site of its own, getChatTranscript, is an existing core route the console did
// not read before (ChatTranscriptController). Background reads are quiet (no global error toast): the
// rail polls them and reports an outage in place.
const QUIET_POLL = [401, 403, 404, 405, 429, 500, 502, 503, 504];
const QUIET_SETUP = [400, 401, 403, 404, 409, 412, 423, 429, 500, 502, 503, 504];

/** One of the person's sessions as the server lists it (GET /api/chat/threads, ChatBranchDtos.ThreadView). */
export interface ServerThread {
  sessionId: string;
  agentId: string;
  /** The first user message, cut server-side; null for a session with none yet. */
  title: string | null;
  createdAt: string;
  updatedAt: string;
}

/** A page of the person's server-side sessions, newest activity first; `nextBefore` pages further back. */
export interface ServerThreadsPage {
  threads: ServerThread[];
  nextBefore: string | null;
}

/** GET /api/assistant/workspace (core WorkspaceFolderController): the projects folder and its folders. */
export interface AssistantWorkspace {
  available: boolean;
  /** local_shell is offered too (the build terminal), not only the file tools. */
  terminal: boolean;
  root: string | null;
  /** Where the projects folder is on this computer, when the install says. */
  hostPath: string | null;
  folders: { name: string; modified: string }[];
}

/** A dev server the Assistant started in the build container (GET /api/assistant/workspace/previews). */
export interface WorkspacePreview {
  port: number;
  folder: string;
  command: string;
  running: boolean;
}

/** POST /api/assistant/workspace/files: where each file landed in the projects folder. */
export interface WorkspaceUpload {
  folder: string;
  files: { name: string; path: string; size: number; textPath: string | null }[];
}

/** GET /api/learning/review/summary: what the person approved in the last seven days and what still waits. */
export interface LearningWeekSummary {
  approved: number;
  /** Of those, memory tidy-ups (merged or forgotten notes). */
  tidied: number;
  pending: number;
  /** English sentence for API consumers; the web composes its own from the counts. */
  text: string;
}

/** One thing the assistant proposes to learn (GET /api/learning/proposals). Nothing applies before approval. */
export interface LearningProposal {
  id: string;
  agentId: string | null;
  /** LESSON | PREFERENCE | ROUTINE | CONSOLIDATION */
  kind: string;
  title: string;
  body: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";
  createdAt: string;
  /** False when no applier exists for the kind yet: approving would answer 409. */
  applicable: boolean;
}

export const assistantApi = {
  /** The Assistant's folder picker. Quiet: an install without a projects folder just has no picker. */
  getWorkspace: (): Promise<AssistantWorkspace | null> =>
    apiFetch(`/api/assistant/workspace`, { skipGetCache: true }).then(
      toJsonQuietOn<AssistantWorkspace | null>(QUIET_POLL)
    ),

  /** Servers the Assistant is running for the Preview pane. Quiet: no terminal means none. */
  listPreviews: (): Promise<WorkspacePreview[]> =>
    apiFetch(`/api/assistant/workspace/previews`, { skipGetCache: true })
      .then(toJsonQuietOn<WorkspacePreview[] | null>(QUIET_POLL))
      .then((rows) => (Array.isArray(rows) ? rows : [])),

  stopPreview: (port: number): Promise<unknown> =>
    apiFetch(`/api/assistant/workspace/previews/${port}`, { method: "DELETE" }),

  /** Creates a top-level folder in the projects folder (201, or 200 when it already exists). */
  createWorkspaceFolder: (name: string): Promise<{ name: string; modified: string }> =>
    apiFetch(`/api/assistant/workspace/folders`, { method: "POST", ...jsonBody({ name }) }).then(
      toJson<{ name: string; modified: string }>
    ),

  /**
   * Saves attached files in the projects folder (POST /api/assistant/workspace/files) so the Assistant can
   * list, search and read them. A PDF comes back with `textPath`, its extracted text.
   */
  uploadWorkspaceFiles: (files: File[], folder?: string | null): Promise<WorkspaceUpload> => {
    const form = new FormData();
    if (folder) form.append("folder", folder);
    for (const f of files) form.append("file", f);
    return apiFetch(`/api/assistant/workspace/files`, { method: "POST", body: form }).then(toJson<WorkspaceUpload>);
  },

  /**
   * GET /api/chat/threads - the signed-in user's chat sessions on the server (ChatThreadController), newest
   * activity first. Quiet: 404 means branching is off on this core (nothing is stored server-side), and any
   * other failure is no reason to hide the local list, so both resolve to null.
   */
  listThreads: (limit?: number, before?: string | null): Promise<ServerThreadsPage | null> => {
    const params = new URLSearchParams();
    if (limit !== undefined) params.set("limit", String(limit));
    if (before) params.set("before", before);
    const query = params.toString();
    return apiFetch(`/api/chat/threads${query ? `?${query}` : ""}`, { skipGetCache: true })
      .then(toJsonQuietOn<ServerThreadsPage | null>(QUIET_POLL))
      .then((page): ServerThreadsPage | null => (page && Array.isArray(page.threads) ? page : null))
      .catch((): ServerThreadsPage | null => null);
  },

  /** DELETE /api/chat/threads/{agentId}/{sessionId} - the server copy of one thread. Quiet: false on 404 (off, or not stored) or any failure. */
  deleteServerThread: (agentId: string, sessionId: string): Promise<boolean> =>
    apiFetch(`/api/chat/threads/${encodeURIComponent(agentId)}/${encodeURIComponent(sessionId)}`, { method: "DELETE" })
      .then((res) => res.ok)
      .catch(() => false),

  /** GET /api/agents/{id}/sessions/{sid}/transcript - the stored conversation of one session (no tool steps). */
  getChatTranscript: (agentId: string, sessionId: string): Promise<ChatTranscript | null> =>
    apiFetch(`/api/agents/${agentId}/sessions/${encodeURIComponent(sessionId)}/transcript`, {
      skipGetCache: true,
    }).then(toJsonQuietOn<ChatTranscript | null>([403, 404])),

  /** One chat turn with the activity trace on (rung 1). Same contract as chatApi.streamChat. */
  streamAssistantTurn: (
    agentId: string,
    message: string,
    sessionId: string | null,
    onChunk: (chunk: string) => void,
    onStep: (event: AgentStepEvent) => void,
    opts: {
      turnRef?: string;
      attachment?: { attachmentKey?: string; mimeType?: string } | null;
      attachments?: { fileName: string; mimeType: string; dataBase64: string }[];
      lifecycle?: ChatResumeLifecycle & { resumable?: boolean; persistPending?: boolean };
    } = {}
  ): Promise<string | null> =>
    chatApi.streamChat(
      agentId,
      message,
      sessionId ?? "",
      onChunk,
      opts.attachment ?? null,
      opts.turnRef,
      opts.attachments,
      undefined,
      opts.lifecycle,
      { onEvent: onStep }
    ),

  /** Rung 2: continue in the background (POST /api/agents/{id}/jobs, 202). One hour, like the design's default. */
  startBackgroundJob: (agentId: string, goal: string, timeoutSeconds = 3600) =>
    agentLifecycleApi.submitAgentJob(agentId, { goal, timeoutSeconds }),

  /** Rung 4: research with citations. */
  getResearchStatus: researchApi.getResearchStatus,
  startResearchRun: researchApi.startResearchRun,

  /** Rung 3: routines. */
  getScheduledTasksConfig: scheduledTasksApi.getScheduledTasksConfig,
  previewScheduledTaskSchedule: scheduledTasksApi.previewScheduledTaskSchedule,
  createScheduledTask: scheduledTasksApi.createScheduledTask,

  /** Work rail: approvals waiting on a person (this session's are claimed inline by PendingToolApprovals). */
  listPendingApprovalsQuiet: (): Promise<ApprovalRequestResponse[]> =>
    governanceApi.listApprovals("PENDING", QUIET_POLL, true) as Promise<ApprovalRequestResponse[]>,

  /** Work rail: jobs that are queued, running or parked on an approval, across the tenant. */
  listLiveJobsQuiet: (): Promise<AgentJobSummaryResponse[] | null> =>
    apiFetch(`/api/agent-jobs?status=live&limit=50`, { skipGetCache: true }).then(
      toJsonQuietOn<AgentJobSummaryResponse[] | null>(QUIET_POLL)
    ),

  /** Learned panel: skills the assistant drafted that wait for the person's yes. Quiet: no skill library means none. */
  listSkillsByStatusQuiet: (status: "PENDING" | "ACTIVE"): Promise<unknown> =>
    apiFetch(`/api/skills?status=${status}&limit=50`, { skipGetCache: true }).then(toJsonQuietOn<unknown>(QUIET_POLL)),

  /** Learned panel: what the assistant proposes to learn (lessons, preferences...), waiting for a yes. Quiet: feature off means none. */
  listLearningProposalsQuiet: (): Promise<LearningProposal[]> =>
    apiFetch(`/api/learning/proposals?status=PENDING`, { skipGetCache: true })
      .then(toJsonQuietOn<LearningProposal[] | null>(QUIET_POLL))
      .then((rows) => (Array.isArray(rows) ? rows : [])),

  /** Approves a proposal, optionally with the person's edit of its text. Rejects (409) when it cannot be applied. */
  approveLearningProposal: (id: string, body?: string): Promise<LearningProposal> =>
    apiFetch(`/api/learning/proposals/${encodeURIComponent(id)}/approve`, {
      method: "POST",
      ...jsonBody(body === undefined ? {} : { body }),
    }).then(toJson<LearningProposal>),

  rejectLearningProposal: (id: string): Promise<LearningProposal> =>
    apiFetch(`/api/learning/proposals/${encodeURIComponent(id)}/reject`, { method: "POST" }).then(
      toJson<LearningProposal>
    ),

  /** Learned panel: "what I learned this week" (daily memory review). Quiet: null when the review is off. */
  getLearningWeekSummaryQuiet: (): Promise<LearningWeekSummary | null> =>
    apiFetch(`/api/learning/review/summary`, { skipGetCache: true }).then(
      toJsonQuietOn<LearningWeekSummary | null>(QUIET_POLL)
    ),

  /** Work rail: this agent's recent background jobs, newest first, whatever their state. */
  listRecentJobsQuiet: (agentId: string): Promise<AgentJobSummaryResponse[] | null> =>
    apiFetch(`/api/agent-jobs?agentId=${encodeURIComponent(agentId)}&limit=30`, { skipGetCache: true }).then(
      toJsonQuietOn<AgentJobSummaryResponse[] | null>(QUIET_POLL)
    ),

  /** Work rail: the person's routines (scheduled tasks), with their next run. Quiet like the other rail reads. */
  listScheduledTasksQuiet: (): Promise<ScheduledTask[] | null> =>
    apiFetch(`/api/scheduled-tasks`, { skipGetCache: true }).then(toJsonQuietOn<ScheduledTask[] | null>(QUIET_POLL)),

  /** One job with its result, for "show it here". */
  getJob: agentLifecycleApi.getAgentJob,

  /** Stop a queued or running job. */
  cancelJob: agentLifecycleApi.cancelAgentJob,

  /**
   * Connect-first: what is connected right now. Uncached on purpose: the client GET cache would serve a
   * "not connected" for minutes after the person connected something, and the check exists to notice that.
   */
  getChannelsStatusFresh: () =>
    apiFetch(`/api/channels/status`, { skipGetCache: true }).then(
      toJsonQuietOn<ChannelsStatusResponse | null>(QUIET_POLL)
    ),
  isGoogleConnectedFresh: () =>
    apiFetch(`/api/channels/google/status`, { skipGetCache: true }).then((res): boolean | null =>
      res.status === 204 ? false : res.ok ? true : null
    ),

  /** /remember <fact>: a shared memory fact the person typed themselves. */
  rememberFact: (content: string) => memoryApi.addMemoryFact(content, "shared"),

  /**
   * What ensureAssistantAgent (components/assistant/assistantAgent.ts) needs. The three follow-up writes
   * after creation are best-effort niceties, so they are quiet: a failure (a locked agent, memory blocks
   * needing an embedding sidecar this install does not run) is swallowed by the caller, never toasted.
   */
  listAgents: agentsApi.listAgents,
  getMemoryQuiet: memoryApi.getMemoryQuiet,
  getDefaultAgentModel: providersApi.getDefaultAgentModel,
  createAgent: agentsApi.createAgent,
  listAvailableTools: agentsApi.listAvailableTools,
  setAgentToolAccess: (agentId: string, allowedToolNames: string[]) =>
    apiFetch(`/api/agents/${agentId}/tool-access`, { method: "PATCH", ...jsonBody({ allowedToolNames }) }).then(
      toJsonQuietOn(QUIET_SETUP)
    ),
  setAgentKnowledgeBase: (agentId: string, enabled: boolean) =>
    apiFetch(`/api/agents/${agentId}/knowledge-base`, { method: "PATCH", ...jsonBody({ enabled }) }).then(
      toJsonQuietOn(QUIET_SETUP)
    ),
  saveMemoryBlock: (label: string, content: string) =>
    apiFetch(`/api/memory/blocks/${encodeURIComponent(label)}`, { method: "PUT", ...jsonBody({ content }) }).then(
      toJsonQuietOn(QUIET_SETUP)
    ),
  updateAgentPrompt: (agentId: string, systemPrompt: string) =>
    apiFetch(`/api/agents/${agentId}/prompt`, { method: "PATCH", ...jsonBody({ systemPrompt }) }).then(
      toJsonQuietOn(QUIET_SETUP)
    ),
  uploadChatAttachment: chatApi.uploadChatAttachment,
};

export type AssistantApi = typeof assistantApi;
