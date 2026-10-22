package com.agenticai.core.workflow.portability;

import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.portability.DifyImportMapper.DNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** The value types shared by {@link DifyImportMapper} and its node-mapping collaborators. */
final class DifyImportModel {

    private DifyImportModel() {}

    enum Level {
        EXACT,
        APPROXIMATE,
        PLACEHOLDER
    }

    /** Where an edge that left a Dify node must start in the mapped graph. */
    record Exit(String nodeId, String handle) {}

    static final class Mapped {
        final List<WorkflowNodeDto> nodes = new ArrayList<>();
        final List<WorkflowEdgeDto> internalEdges = new ArrayList<>();
        String entryId;
        Level level = Level.EXACT;
        String reason = "";
        final List<String> notes = new ArrayList<>();

        /**
         * Maps a Dify {@code sourceHandle} to the mapped node and handle an outgoing edge starts
         * from.
         */
        Function<String, Exit> exits;

        String label = "";
    }

    /**
     * Per-node scratch state: notes, and whether the node carried references or needs a person's
     * review.
     */
    static final class Ctx implements DifyReferences.Sink {
        final DNode node;
        final JsonNode data;
        final List<String> notes = new ArrayList<>();
        boolean approximate;
        boolean refs;
        boolean inHttp;
        private final DifyReferences references;

        Ctx(DifyReferences references, DNode node, JsonNode data) {
            this.references = references;
            this.node = node;
            this.data = data;
        }

        void lose(String note) {
            approximate = true;
            notes.add(note);
        }

        @Override
        public void refsSeen() {
            refs = true;
        }

        @Override
        public void unconverted(String note) {
            approximate = true;
            notes.add(note);
        }

        @Override
        public boolean inHttp() {
            return inHttp;
        }

        /**
         * Dify text with its variable references rewritten; marks the node as carrying references.
         */
        String text(String raw) {
            if (raw == null) {
                return null;
            }
            return references.rewrite(this, raw);
        }
    }
}
