export type QuickTaskChannel = "WHATSAPP" | "SMS" | "CALL" | "LOG";
export type QuickTaskRunStatus = "RUNNING" | "COMPLETED" | "FAILED";

/** QuickTaskRunSummaryResponse - one row of GET /api/quick-task/recent. */
export interface QuickTaskRunSummary {
  taskId: string;
  channel: QuickTaskChannel;
  status: QuickTaskRunStatus;
  requestMessage: string;
  startedAt: string;
}

/** QuickTaskRunDetailResponse - GET /api/quick-task/{taskId}. */
export interface QuickTaskRunDetail extends QuickTaskRunSummary {
  agentId: string | null;
  resultText: string | null;
  deliveryNote: string | null;
  errorMessage: string | null;
  completedAt: string | null;
  toolCalls:
    | {
        toolName: string;
        toolInput: string | null;
        toolOutput: string | null;
        errorMessage: string | null;
        startedAt: string | null;
        completedAt: string | null;
      }[]
    | null;
  agentName?: string | null;
  providerName?: string | null;
  model?: string | null;
}
