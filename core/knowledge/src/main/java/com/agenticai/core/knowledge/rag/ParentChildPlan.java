package com.agenticai.core.knowledge.rag;

import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What ingestion needs to store one document parent-child: the flat list of child texts to embed
 * (in order), the {@code metadata} JSON for each child (the document's {@code source_type} plus
 * {@code parent_id}, {@code child_start}, {@code child_end} - offsets into the parent's text), and
 * the parent rows to persist. Built from {@link ParentChildChunker}'s output, so ingestion stays a
 * thin caller: embed {@code pieces}, insert {@code parentRows}, insert each child with {@code
 * metadata.get(i)}.
 */
public record ParentChildPlan(
        List<String> pieces, List<String> metadata, List<ParentChunkStore.ParentRow> parentRows) {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    public static ParentChildPlan build(
            List<ParentChildChunker.Parent> parents, String sourceType) {
        List<String> pieces = new ArrayList<>();
        List<String> metadata = new ArrayList<>();
        List<ParentChunkStore.ParentRow> rows = new ArrayList<>(parents.size());
        for (ParentChildChunker.Parent parent : parents) {
            UUID parentId = UUID.randomUUID();
            rows.add(new ParentChunkStore.ParentRow(parentId, parent.index(), parent.text()));
            for (ParentChildChunker.Child child : parent.children()) {
                pieces.add(child.text());
                ObjectNode node = MAPPER.createObjectNode();
                node.put("source_type", sourceType);
                node.put("parent_id", parentId.toString());
                node.put("child_start", child.startInParent());
                node.put("child_end", child.endInParent());
                metadata.add(node.toString());
            }
        }
        return new ParentChildPlan(List.copyOf(pieces), List.copyOf(metadata), List.copyOf(rows));
    }
}
