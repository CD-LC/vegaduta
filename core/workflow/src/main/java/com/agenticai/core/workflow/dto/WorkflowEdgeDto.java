package com.agenticai.core.workflow.dto;

/**
 * Mirrors a React Flow edge object. {@code sourceHandle} is how a Condition
 * node's two outgoing paths are told apart - the canvas wires one edge's
 * handle to {@code "true"} and the other to {@code "false"} when a user
 * drags a connection off a Condition node's two output ports.
 */
public record WorkflowEdgeDto(
    String id,
    String source,
    String target,
    String sourceHandle,
    String targetHandle
) {
}
