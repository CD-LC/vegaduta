package com.agenticai.core.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A knowledge collection holds vectors from one embedding source, and the live source is a different
 * one (the operator switched {@code app.embeddings.*}). Writing the new source's vectors beside the old
 * ones would make the collection's search silently wrong, so the write is refused with 409 and the
 * way out: re-embed the collection (which rebuilds every vector with the live source and rebinds it).
 */
public class EmbeddingModelMismatchException extends ResponseStatusException {

    public EmbeddingModelMismatchException(KnowledgeCollection collection, String liveSource) {
        super(HttpStatus.CONFLICT, message(collection, liveSource));
    }

    static String message(KnowledgeCollection collection, String liveSource) {
        return "Knowledge collection \"" + collection.getName() + "\" was embedded with ["
            + collection.getEmbeddingFingerprint() + "] but the embeddings source is now [" + liveSource
            + "]. Vectors from different models cannot be compared or mixed. Re-embed the collection with the "
            + "current source: POST /api/knowledge/collections/" + collection.getId()
            + "/reembed with its current targetModel \"" + collection.getEmbeddingModel()
            + "\", or switch app.embeddings back to the previous provider/model.";
    }
}
