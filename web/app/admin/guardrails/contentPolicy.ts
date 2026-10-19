// Pure logic for the LLM-gateway content-policy card (core/chat
// LlmGatewayContentPolicyController). Kept free of React so it is unit-testable.

export type InjectionAction = "FLAG" | "BLOCK";

/** Mirrors the controller's ContentPolicyResponse / ContentPolicyRequest. */
export interface ContentPolicy {
  piiRedactionEnabled: boolean;
  piiRedactEmail: boolean;
  piiRedactPhone: boolean;
  piiRedactGovernmentId: boolean;
  piiRedactCreditCard: boolean;
  injectionScreeningEnabled: boolean;
  injectionScreeningAction: InjectionAction;
}

/** What a tenant with no stored row gets: both checks off, categories pre-ticked. */
export const DEFAULT_CONTENT_POLICY: ContentPolicy = {
  piiRedactionEnabled: false,
  piiRedactEmail: true,
  piiRedactPhone: true,
  piiRedactGovernmentId: true,
  piiRedactCreditCard: true,
  injectionScreeningEnabled: false,
  injectionScreeningAction: "FLAG",
};

export const PII_CATEGORY_FIELDS = [
  { key: "piiRedactEmail", label: "Email addresses" },
  { key: "piiRedactPhone", label: "Phone numbers" },
  { key: "piiRedactGovernmentId", label: "Government IDs (SSN, Aadhaar, PAN)" },
  { key: "piiRedactCreditCard", label: "Payment card numbers" },
] as const;

/** Coerces an untrusted server payload into a full policy, filling gaps with defaults. */
export function normalizeContentPolicy(raw: unknown): ContentPolicy {
  const d = DEFAULT_CONTENT_POLICY;
  if (!raw || typeof raw !== "object") return { ...d };
  const r = raw as Record<string, unknown>;
  const bool = (v: unknown, fallback: boolean) => (typeof v === "boolean" ? v : fallback);
  const action = typeof r.injectionScreeningAction === "string" ? r.injectionScreeningAction.toUpperCase() : "";
  return {
    piiRedactionEnabled: bool(r.piiRedactionEnabled, d.piiRedactionEnabled),
    piiRedactEmail: bool(r.piiRedactEmail, d.piiRedactEmail),
    piiRedactPhone: bool(r.piiRedactPhone, d.piiRedactPhone),
    piiRedactGovernmentId: bool(r.piiRedactGovernmentId, d.piiRedactGovernmentId),
    piiRedactCreditCard: bool(r.piiRedactCreditCard, d.piiRedactCreditCard),
    injectionScreeningEnabled: bool(r.injectionScreeningEnabled, d.injectionScreeningEnabled),
    injectionScreeningAction: action === "BLOCK" ? "BLOCK" : "FLAG",
  };
}

export function isContentPolicyDirty(a: ContentPolicy, b: ContentPolicy): boolean {
  return (Object.keys(DEFAULT_CONTENT_POLICY) as (keyof ContentPolicy)[]).some((k) => a[k] !== b[k]);
}

/** True when PII redaction is on but every category is unticked - it would redact nothing. */
export function isPiiRedactionInert(p: ContentPolicy): boolean {
  return (
    p.piiRedactionEnabled && !p.piiRedactEmail && !p.piiRedactPhone && !p.piiRedactGovernmentId && !p.piiRedactCreditCard
  );
}
