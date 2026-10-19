// Shapes of core's /api/memory responses (UserMemoryController and
// UserMemoryProvenanceController). Kept beside the page rather than in the
// shared lib/api/types.ts because nothing outside the memory surfaces reads
// them. Every field newer than the first release is optional: an older core
// simply omits it and the matching control stays hidden.

export type MemoryBlock = { label: string; content: string; updatedAt?: string | null };

export type MemoryScope = "agent" | "shared";

export type MemoryFact = {
  id: string;
  scope: MemoryScope;
  agentId?: string | null;
  content: string;
  createdAt?: string | null;
  lastUsedAt?: string | null;
  useCount?: number;
};

export type MemoryOverview = {
  available: boolean;
  blocksEnabled: boolean;
  limits: { maxCharsPerBlock: number; maxProjectBlocks: number; maxFactChars: number };
  blocks: MemoryBlock[];
  facts: MemoryFact[];
  /** false or absent: add-fact, forget-everything and export are not offered. */
  selfServiceEnabled?: boolean;
  /** false or absent: the active-project pointer cannot be written. */
  activeProjectEnabled?: boolean;
};

export type MemoryFactSource = {
  agentId: string | null;
  sessionId: string | null;
  learnedAt: string | null;
  /** true when the conversation that saved the fact was found in the tool log. */
  matched: boolean;
};

export type ForgetAllResult = {
  agentFacts: number;
  sharedAndBlocks: number;
  graphEntities: number;
  /** false: the linked-facts (graph) erase failed; the stored rows are gone regardless. */
  graphErased: boolean;
};

/** The slice of an agent this surface needs (GET /api/agents rows carry more). */
export type MemoryAgent = {
  id: string;
  name: string;
  shareMemoryWithTenant?: boolean;
  /** false: core rejects every settings change for this agent (AgentResponse.editable). Absent = allowed. */
  editable?: boolean;
};
