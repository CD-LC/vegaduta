import { apiFetch, toJson, jsonBody } from "./core";

// Per-user budget top-up (core/app BudgetTopUpController, /api/budget/top-up). The credit lands on the very
// counter the chat path checks. Nothing is credited until confirm() succeeds; the server takes amount and
// modality from its own pending-order record, never from the client.

export interface BudgetTopUpOption {
  usdAmount: number;
  amountInSubunits: number;
  label: string;
}

export interface BudgetTopUpOptions {
  enabled: boolean;
  currency: string;
  minUsd: number;
  maxUsd: number;
  gatewayName: string;
  publicKeyId: string;
  options: BudgetTopUpOption[];
}

export interface BudgetTopUpOrder {
  gatewayName: string;
  publicKeyId: string;
  orderId: string;
  amountInSubunits: number;
  currency: string;
  modality: string;
  amountUsd: number;
  status: string;
}

export interface BudgetTopUpConfirmation {
  credited: boolean;
  alreadyProcessed: boolean;
  modality: string;
  amountUsd: number;
}

export const budgetTopUpApi = {
  getBudgetTopUpOptions: (): Promise<BudgetTopUpOptions> =>
    apiFetch(`/api/budget/top-up/options`).then(toJson<BudgetTopUpOptions>),
  purchaseBudgetTopUp: (amountUsd: number, modality = "text"): Promise<BudgetTopUpOrder> =>
    apiFetch(`/api/budget/top-up`, { method: "POST", ...jsonBody({ modality, amountUsd }) }).then(
      toJson<BudgetTopUpOrder>,
    ),
  confirmBudgetTopUp: (orderId: string, paymentId: string, signature: string): Promise<BudgetTopUpConfirmation> =>
    apiFetch(`/api/budget/top-up/confirm`, { method: "POST", ...jsonBody({ orderId, paymentId, signature }) }).then(
      toJson<BudgetTopUpConfirmation>,
    ),
};
