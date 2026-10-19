// One arm of an agent's RL bandit policy - see listRlArms/createRlArm below.
// alpha/beta are the raw Thompson-sampling Beta posterior parameters and BOTH
// START AT 1.0 (uniform prior, see V113__policy_arm.sql), so `alpha` is NOT an
// observed success count: the observed reward earned so far is (alpha - 1),
// over `pulls` trials. Anything rendering these must divide that out rather
// than showing alpha/(alpha+beta), which reads 50% for an arm that has never
// been tried. See rl-arms.ts's observedRewardRate().
export interface RlPolicyArm {
  id: string;
  armKey: string;
  armConfigJson: string;
  contextBucket: string | null;
  pulls: number;
  alpha: number;
  beta: number;
}

// One row of GET /api/providers/{provider}/models (ProviderModelCatalogService).
// Everything beyond modelId is best-effort catalog metadata - render it when
// present, never require it. `description`/`deprecated`/`source` are additive
// fields still landing server-side, so they stay optional here either way.
// One row of the tenant secrets vault. Mirrors core/secrets dto/SecretResponse.
// There is no `value` field on purpose - see the api.listSecrets comment.
export interface VaultSecret {
  id: string;
  name: string;
  /** The paste-into-config string, e.g. "{{secret.STRIPE_KEY}}". */
  reference: string;
  scope: "TENANT" | "USER";
  kind: "API_KEY" | "PASSWORD" | "TOKEN" | "BASIC_AUTH" | "CUSTOM";
  description: string | null;
  /** Last 4 plaintext characters, or null for secrets shorter than 8 chars. */
  preview: string | null;
  status: "ACTIVE" | "REVOKED";
  /**
   * Whether sandboxed tenant code may receive this value. Default false, and
   * that default is the security property: anyone who can author a code tool
   * in the workspace can read any secret with this set, because a program
   * holding a plaintext value can always return it transformed past redaction.
   */
  codeToolAccess: boolean;
  /**
   * Destination host patterns (SEC-006, V365.4): exact `api.example.com` or
   * `*.example.com`. Empty = the secret is never sent to a URL by http_request,
   * workflow HTTP nodes or OpenAPI sources. May be absent from an older core.
   */
  allowedHosts?: string[];
  expiresAt: string | null;
  expired: boolean;
  lastUsedAt: string | null;
  rotatedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface VaultSecretStoreRequest {
  name: string;
  scope: "TENANT" | "USER";
  kind: VaultSecret["kind"];
  description?: string | null;
  value: string;
  expiresAt?: string | null;
  /** Omit or false unless the secret is genuinely meant for a code tool. */
  codeToolAccess?: boolean;
  /** Omitted on rotation = keep the current list; omitted on create = none. */
  allowedHosts?: string[];
}
