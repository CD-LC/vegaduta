import { apiFetch, jsonBody, toJson, triggerBrowserDownload } from "./core";

// Per-agent cost and ROI (core/app common/roi, RoiController): /api/tenant/roi/**.
// Reads need VIEW_USAGE, writes (prices, value assumptions, outcomes) need MANAGE_BILLING.
//
// Every money field is recorded data or null. `null` means "not known / no data", never zero:
// the page must not turn it into 0. Deliberately NOT spread into the `api` barrel (lib/api.ts):
// only the ROI page reads this.

/** BigDecimal on the server: a JSON number, tolerated as a string too. */
export type Money = number | string | null;

export interface AgentRoiRowDto {
  agentId: string;
  agentName: string;
  conversations: number;
  /** Conversations the agent resolved (idle-close guesses only when includeAssumed). */
  resolved: number;
  resolvedConfirmed: number;
  resolvedAssumed: number;
  resolvedByHuman: number;
  handedToHuman: number;
  abandoned: number;
  unresolved: number;
  humanCorrected: number;
  /** 0..1, null when there are no conversations. */
  handoverRate: Money;
  humanCorrectionRate: Money;
  approvalsDecided: number;
  approvalsDenied: number;
  approvalDenialRate: Money;
  byokEstimatedCostUsd: Money;
  platformHostedEstimatedCostUsd: Money;
  estimatedCostUsd: Money;
  /** null when nothing was resolved. */
  estimatedCostPerResolvedUsd: Money;
  /** False when some calls had no applicable price or no usage: the cost is then a lower bound. */
  costComplete: boolean;
  platformChargesMinor: number;
  platformChargesCurrency: string | null;
  valuePerResolution: Money;
  valueCurrency: string | null;
  minutesSavedPerResolution: Money;
  valueTotal: Money;
  minutesSavedTotal: Money;
  netValue: Money;
  roiPercent: Money;
  /** NO_ASSUMPTIONS | NO_VALUE_ASSUMPTION | CURRENCY_MISMATCH | COST_INCOMPLETE | NO_COST_RECORDED | OK */
  roiNote: string;
}

export interface AgentRoiResponseDto {
  from: string;
  to: string;
  includeAssumedResolutions: boolean;
  /** "based on your assumptions" - printed next to every ROI figure. */
  roiBasis: string;
  agents: AgentRoiRowDto[];
}

export interface BudgetDto {
  monthStart: string;
  plan: string | null;
  spentUsd: Money;
  /** null when the plan has no monthly cap. */
  capUsd: Money;
  percentUsed: Money;
  capConfigured: boolean;
  spendBasis: string;
  alertsFired: number[];
}

export interface PriceDto {
  id: string;
  providerName: string;
  modelPrefix: string;
  inputPerMillion: Money;
  outputPerMillion: Money;
  cacheWritePerMillion: Money;
  cacheReadPerMillion: Money;
  effectiveFrom: string;
  setAt: string;
  setBy: string | null;
  note: string | null;
}

export interface UnpricedModelDto {
  providerName: string;
  model: string;
  calls: number;
  totalTokens: number;
  reason: string;
}

export interface PriceListDto {
  prices: PriceDto[];
  unpricedModels: UnpricedModelDto[];
  currency: string;
}

export interface PriceRequestDto {
  providerName: string;
  modelPrefix: string;
  inputPerMillion: number | null;
  outputPerMillion: number | null;
  cacheWritePerMillion?: number | null;
  cacheReadPerMillion?: number | null;
  effectiveFrom?: string;
  note?: string;
}

export interface AssumptionDto {
  agentId: string;
  valuePerResolution: Money;
  valueCurrency: string | null;
  minutesSavedPerResolution: Money;
  updatedAt: string | null;
  basis: string;
}

export interface AssumptionRequestDto {
  valuePerResolution: number | null;
  valueCurrency: string | null;
  minutesSavedPerResolution: number | null;
}

/** Inclusive ISO dates (yyyy-MM-dd). Omit both for the server default (last 30 days). */
export interface RoiRange {
  from?: string;
  to?: string;
  includeAssumed?: boolean;
}

export function roiQuery(range: RoiRange = {}): string {
  const params = new URLSearchParams();
  if (range.from) params.set("from", range.from);
  if (range.to) params.set("to", range.to);
  if (range.includeAssumed) params.set("includeAssumed", "true");
  const query = params.toString();
  return query ? `?${query}` : "";
}

const BASE = "/api/tenant/roi";

export const roiApi = {
  getAgents: (range?: RoiRange): Promise<AgentRoiResponseDto> =>
    apiFetch(`${BASE}/agents${roiQuery(range)}`).then(toJson<AgentRoiResponseDto>),
  getBudget: (): Promise<BudgetDto> => apiFetch(`${BASE}/budget`).then(toJson<BudgetDto>),
  getPrices: (range?: RoiRange): Promise<PriceListDto> =>
    apiFetch(`${BASE}/prices${roiQuery({ from: range?.from, to: range?.to })}`).then(toJson<PriceListDto>),
  setPrice: (body: PriceRequestDto): Promise<PriceDto> =>
    apiFetch(`${BASE}/prices`, { method: "POST", ...jsonBody(body) }).then(toJson<PriceDto>),
  getAssumption: (agentId: string): Promise<AssumptionDto> =>
    apiFetch(`${BASE}/agents/${encodeURIComponent(agentId)}/assumptions`).then(toJson<AssumptionDto>),
  setAssumption: (agentId: string, body: AssumptionRequestDto): Promise<AssumptionDto> =>
    apiFetch(`${BASE}/agents/${encodeURIComponent(agentId)}/assumptions`, { method: "PUT", ...jsonBody(body) }).then(
      toJson<AssumptionDto>,
    ),
  /** Downloads the same table as a CSV; the server names the file. */
  downloadAgentsCsv: async (range?: RoiRange): Promise<void> => {
    const res = await apiFetch(`${BASE}/agents.csv${roiQuery(range)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const blob = await res.blob();
    const name = /filename="([^"]+)"/.exec(res.headers.get("Content-Disposition") ?? "")?.[1] ?? "agent-roi.csv";
    triggerBrowserDownload(blob, name);
  },
};
