/** KnowledgeCollectionResponse (core/knowledge). */
export interface KnowledgeCollectionResponse {
  id: string;
  name: string;
  description: string | null;
  parentCollectionId: string | null;
  isDefault: boolean;
  embeddingModel: string | null;
  textSearchConfig: string | null;
  fileCount: number;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
  embeddingStatus: string | null;
  chunkSize: number | null;
  chunkOverlap: number | null;
}

/** A selectable embedding model for a new collection. */
export interface EmbeddingModelOption {
  key: string;
  label: string;
  isDefault?: boolean;
}

/** ProviderResponse (core/providers) - one BYOK provider key row. */
export interface ProviderResponse {
  id: string;
  providerName: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  lastValidatedAt: string | null;
}
