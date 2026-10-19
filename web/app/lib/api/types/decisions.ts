import type { CapabilityCredential } from "./skills";

/** Which key the current tenant's decision calls resolve to. */
export type DecisionKeySource = "TENANT" | "PLATFORM" | "ENV";

export type DecisionStatus = {
  /** Platform kill-switch - false means no decision provider runs. */
  enabled: boolean;
  /** Enabled AND a usable key resolves for this tenant right now. */
  configured: boolean;
  /** The provider_key of the model that would serve a call - absent when none. */
  providerKey: string | null;
  /** Whose key is in play for this tenant - absent when nothing resolves. */
  source: DecisionKeySource | null;
  /** The tenant's own stored credential for the active provider, when one exists (never the key). */
  tenantCredential: CapabilityCredential | null;
};

/**
 * The tenant's decision policy (PUT /api/capabilities/decision/policy). The
 * overrides map is raw: a MISSING key = "inherit the platform default", which
 * is also how a field is reset (PUT omits the key to reset it). The platform
 * flag stays the hard gate - a true here cannot resurrect a feature the
 * deployment disabled; a false opts the tenant out of a feature the platform
 * left on.
 */
export type DecisionPolicy = {
  capability: string;
  overrides: {
    /** The decision model's say in per-turn cost routing (QueryComplexityClassifier). */
    costRouting?: boolean;
    /** The decision model's say in Quick Task agent selection (AgentRelevanceSelector). */
    agentRouting?: boolean;
    /** Confidence bar for "this turn is simple" - bounded 0.5..0.99 server-side. */
    simpleThreshold?: number;
  };
  updatedAt: string | null;
};

export type DecisionQuestion = {
  type: "noul" | "choice" | "score";
  instructions: string;
  /** choice: option key -> description; score: ordered level descriptions. */
  criteria?: Record<string, string> | string[];
};

export type DecisionPlaygroundResponse = {
  model: string;
  keySource: DecisionKeySource;
  answers: Record<string, unknown>;
  usage: Record<string, unknown>;
};

// ---------------------------------------------------------------------------
// FE-030 response models. Before these, toJson returned `any`, so each of
// these endpoints was untyped and several pages re-declared their own narrow
// slice of the payload. Shaped after the backend DTO (record components in
// core/), enums as plain strings unless a caller needs the literal union.
// The per-page copies are structurally compatible with these and can be
// swapped for imports from here (their comments already ask for that).
// ---------------------------------------------------------------------------
