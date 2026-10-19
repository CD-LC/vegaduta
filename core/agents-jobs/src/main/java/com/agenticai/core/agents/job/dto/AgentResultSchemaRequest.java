package com.agenticai.core.agents.job.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code PUT /api/agents/{agentId}/result-schema}.
 *
 * <p>Carries the schema as a raw JSON STRING rather than as a nested object,
 * even though it is itself JSON. Two reasons, both concrete: a nested object
 * would be round-tripped through Jackson on the way in and again on the way out,
 * so the document a tenant stored is not byte-for-byte the document they get
 * back (key order and formatting change, which makes "did my change save?"
 * needlessly hard to answer); and the string is what gets snapshotted onto every
 * {@code agent_job} row and rendered into the prompt, so keeping it a string end
 * to end means there is exactly one representation and no place for the two to
 * diverge.
 *
 * <p>Only {@code @NotBlank} here. Everything that actually makes a schema
 * USABLE - it parses, it is an object, it is under the size ceiling, it has no
 * remote {@code $ref} - is checked by {@code JobResultSchemaValidator}, because
 * those rules are needed identically by the per-job schema on job submission,
 * where there is no request DTO to hang annotations on. One implementation of
 * "is this schema acceptable", not two that drift.
 */
public record AgentResultSchemaRequest(
    @NotBlank(message = "schemaJson is required") String schemaJson
) {}
