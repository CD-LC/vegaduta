// Shapes of core's unified-memory REST surface (ADR-0028 WP6):
// MemoryItemController (/api/agents/{id}/memory-items) and
// MemoryItemExportController (/api/memory-items/export). Kept beside the page,
// like memoryTypes.ts, because nothing outside the memory surfaces reads them.
// Every field is optional where an older core may omit it.

export type MemoryItem = {
  id: string;
  agentId: string;
  /** EPISODIC | SEMANTIC | PROCEDURAL | SELF_MODEL (a forward-compatible string on the wire). */
  kind?: string;
  content: string;
  /** TRUSTED | QUARANTINED. */
  trust?: string;
  /** PENDING | ACTIVE | REJECTED | SUPERSEDED | REVERTED. */
  status?: string;
  /** HUMAN | AGENT | SYSTEM … who wrote the item. */
  writer?: string;
  sourceType?: string | null;
  sourceRef?: string | null;
  sourceTaint?: string[];
  modelGenerated?: boolean;
  generation?: number;
  /** Data-subject keys the item is about (the signed-in user's export matches on these). */
  subjectKeys?: string[];
  version?: number;
  supersedesId?: string | null;
  createdAt?: string | null;
  expiresAt?: string | null;
  approvedBy?: string | null;
  approvedAt?: string | null;
  /** A person pinned it: consolidation never forgets a pinned item. */
  pinned?: boolean;
};

/** One lineage hop: which upstream table/row the item was derived from. */
export type MemoryItemSource = {
  sourceTable?: string;
  sourceKey?: string;
  sourceTrust?: string;
};

/** One memory_event row; never carries memory text. */
export type MemoryItemEvent = {
  id: string;
  /** CREATED | APPROVED | REJECTED | EDITED | REVERTED | EXPIRED | ERASED | PINNED | UNPINNED | REFUSED. */
  event?: string;
  actorType?: string;
  actorRef?: string | null;
  fromStatus?: string | null;
  toStatus?: string | null;
  at?: string;
};

/** GET /api/agents/{id}/memory-items/{itemId}: lineage + events are populated for EDITOR only. */
export type MemoryItemDetail = {
  item: MemoryItem;
  lineage: MemoryItemSource[];
  events: MemoryItemEvent[];
};

/** GET /api/memory-items/export. */
export type MemoryItemExport = {
  exportedAt?: string;
  subject?: string;
  items: MemoryItem[];
};
