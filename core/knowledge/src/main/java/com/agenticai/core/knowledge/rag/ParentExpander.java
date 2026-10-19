package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.dto.SearchResultResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Small-to-big hand-off for parent-child collections: a search ranks (and reranks) the precise CHILD
 * chunks, then this swaps each hit's text for its PARENT passage and drops later hits that share a
 * parent already returned (two children of one parent would otherwise hand the model the same
 * passage twice and burn the topK budget on it).
 *
 * <p>What the citation keeps pointing at: {@code id}, {@code chunkIndex}, {@code source},
 * {@code documentId} and {@code collectionId} stay those of the winning child, and the child's span
 * inside the returned parent text is reported as {@code startChar}/{@code endChar}/{@code quote}, so
 * a UI can highlight exactly which part of the (larger) context matched. Hits with no parent link
 * (flat chunks, or a parent that could not be loaded) pass through unchanged and are never de-duped.
 */
public final class ParentExpander {

    /** A child chunk's parent: the parent's id and text, and the child's [start, end) inside that text. */
    public record ParentLink(UUID parentId, String parentText, int childStart, int childEnd) {}

    private ParentExpander() {
    }

    /**
     * @param ranked hits best first
     * @param links  child chunk id to its parent (absent = flat chunk)
     */
    public static List<SearchResultResponse> expand(List<SearchResultResponse> ranked, Map<UUID, ParentLink> links) {
        if (links == null || links.isEmpty()) {
            return ranked;
        }
        Set<UUID> seenParents = new HashSet<>();
        List<SearchResultResponse> out = new ArrayList<>(ranked.size());
        for (SearchResultResponse hit : ranked) {
            ParentLink link = links.get(hit.id());
            if (link == null || link.parentText() == null) {
                out.add(hit);
                continue;
            }
            if (!seenParents.add(link.parentId())) {
                continue;
            }
            String parentText = link.parentText();
            boolean spanValid = link.childStart() >= 0 && link.childStart() < link.childEnd() && link.childEnd() <= parentText.length();
            out.add(new SearchResultResponse(
                hit.id(), hit.source(), hit.chunkIndex(), parentText, hit.collectionId(), hit.distance(), hit.documentId(),
                hit.sourceUrl(),
                spanValid ? link.childStart() : null,
                spanValid ? link.childEnd() : null,
                spanValid ? parentText.substring(link.childStart(), link.childEnd()) : null
            ));
        }
        return out;
    }
}
