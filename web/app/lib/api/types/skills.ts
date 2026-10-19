export type SkillSummary = {
  id: string | null;
  name: string;
  description: string;
  source: "BUILT_IN" | "CUSTOM";
  enabled: boolean;
  fileCount: number;
  // Review provenance (V395): null on built-ins; "agent:*" marks a
  // machine-authored draft (e.g. "agent:skill-distiller") that is staged
  // disabled until an admin enables it. Undefined on older backends.
  createdBy?: string | null;
  // A staged agent-authored REWRITE is parked under GET .../improvement.
  pendingImprovement?: boolean;
};

export type SkillRegistryEntry = {
  id: string;
  name: string;
  author: string;
  description: string;
  githubUrl: string;
  registryUrl: string;
  stars: number;
};
export type SkillRegistrySearch = { skills: SkillRegistryEntry[]; page: number; totalPages: number; total: number };

export type SkillDetail = {
  id: string | null;
  name: string;
  description: string;
  body: string;
  source: "BUILT_IN" | "CUSTOM";
  skillMd: string;
  files: { path: string; sizeBytes: number }[];
};

// GET /api/skills/{id}/improvement - a staged agent-authored rewrite parked
// for admin review (V243). 404 → null when nothing is staged.
export type PendingSkillImprovement = {
  skillId: string;
  name: string;
  description: string;
  body: string;
  files: Record<string, string>;
  rationale: string;
  proposedBy: string;
  proposedAt: string;
};

// GET|POST /api/agents/{id}/skills/distill - repeated successful tool-call
// patterns detected in this agent's runs (V395).
export type DistillPattern = {
  tools: string[];
  distinctSessions: number;
  runs: number;
  meanReward: number;
  sampleGoals: string[];
};
export type DistillResult = {
  patterns: DistillPattern[];
  proposedSkillNames: string[];
  skipped: Record<string, string>;
};

export type AgentSkills = {
  skillsEnabled: boolean;
  allowedSkillNames: string[];
  activeSkills: SkillSummary[];
};

// --- Generic capability registry (core/providers CapabilityController) -----
// Tenant-facing BYOK + policy over the provider-agnostic /api/capabilities
// surface: one catalog of capabilities, each listing the providers that can
// serve it, plus per-provider tenant credentials. The DECISION capability
// (cost routing, Quick Task agent selection) adds a status/playground slice
// under /api/capabilities/decision/* (DecisionCapabilityController).

export type CapabilityCredential = {
  id: string;
  providerKey: string;
  capabilities: string[];
  status: "ACTIVE" | "INACTIVE" | "INVALID";
  createdAt: string;
  updatedAt: string;
};

export type CapabilityProvider = {
  providerKey: string;
  displayName: string;
  credential: CapabilityCredential | null;
};

export type CapabilityDescriptor = {
  key: string;
  providers: CapabilityProvider[];
};
