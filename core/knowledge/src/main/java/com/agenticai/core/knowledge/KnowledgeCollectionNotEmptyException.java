package com.agenticai.core.knowledge;

import java.util.UUID;

/**
 * Thrown when deleting a {@link KnowledgeCollection} that either still has
 * {@code knowledge_chunks} directly in it, or is the tenant's default
 * collection (which always needs to exist as the fallback landing spot for
 * uploads with no explicit collection - see {@code migration V15}'s header
 * comment). A raw Postgres FK-violation from the {@code knowledge_chunks}
 * table also backstops the "still has chunks" case at the database level
 * (deleting a collection whose descendants still hold chunks cascades
 * through {@code parent_collection_id} and would hit that constraint) -
 * this exception is what {@link KnowledgeCollectionService} throws for the
 * common, directly-checked case, giving a clean 409 instead of a leaked SQL
 * error for the same underlying reason.
 */
public class KnowledgeCollectionNotEmptyException extends RuntimeException {

    public KnowledgeCollectionNotEmptyException(UUID collectionId, String reason) {
        super("Cannot delete this knowledge collection: " + reason);
    }
}
