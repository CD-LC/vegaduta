// Deep research (core/agents/.../research, GET /api/research/status and the
// /api/research/runs facade over agent jobs). A run IS an agent job row, so
// run rows reuse the job shapes the /jobs screens already read; only the
// status probe and the structured report get their own types.
export type ResearchDepth = "auto" | "simple" | "comparison" | "complex";

export interface ResearchLimits {
  maxSearchesPerRun: number;
  maxFetchesPerRun: number;
  maxSources: number;
  // Wall-clock cap for ONE research pass; a run may make up to two.
  totalDeadlineSeconds: number;
  // 0 = not configured.
  runBudgetUsd: number;
  // 0 = no daily cap.
  maxRunsPerUserPerDay: number;
  runTimeoutSeconds: number;
}

export interface ResearchStatus {
  enabled: boolean;
  searchConfigured: boolean;
  runsEnabled: boolean;
  limits: ResearchLimits;
  // null when there is no daily cap.
  remainingToday: number | null;
}

export interface ResearchRunStartPayload {
  question: string;
  depth?: ResearchDepth;
  seedUrls?: string[];
  language?: string;
}

export interface ResearchQuote {
  quote: string;
  claim?: string;
  // Free text copied by the model from the citation check (SUPPORTED,
  // WEAK_SUPPORT, NOT_SUPPORTED, QUOTE_NOT_FOUND ...) - not a closed enum.
  verdict?: string;
}

export interface ResearchSource {
  id: string;
  url: string;
  title?: string;
  quotes?: ResearchQuote[];
}

// agent_job.result_json of a succeeded research run. Everything optional may
// be absent - readers go through parseResearchResult (components/research).
export interface ResearchResult {
  title: string;
  reportMarkdown: string;
  sources: ResearchSource[];
  unanswered?: string[];
  partial?: boolean;
}
