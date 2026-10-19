package com.agenticai.core.workflow;

import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Graph (de)serialisation shared by the workflow capability services. */
@Component
class WorkflowGraphCodec {

    private final ObjectMapper objectMapper;

    WorkflowGraphCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String writeGraph(WorkflowGraphDto graph) {
        try {
            return objectMapper.writeValueAsString(graph);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Could not serialize workflow graph: " + e.getMessage(), e);
        }
    }

    /** Same parse, same corrupt-graph contract the facade always had. */
    WorkflowGraphDto readGraph(String graphJson) {
        try {
            return objectMapper.readValue(graphJson, WorkflowGraphDto.class);
        } catch (Exception e) {
            throw new WorkflowGraphCorruptException(
                    "Stored workflow graph is corrupt: " + e.getMessage(), e);
        }
    }
}
