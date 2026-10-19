package com.agenticai.core.agents.core;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * An agent's JSON schemas, embedded in {@link Agent}; the columns keep their names.
 *
 * <p>{@code inputSchemaJson} / {@code outputSchemaJson} (migration V170) are PLATFORM-GENERATED and
 * regenerated wholesale by {@code AgentSchemaService#refresh}; both are null until it first runs.
 * {@code schemaVersion} is 0 for "never generated" and bumps on every regeneration.
 *
 * <p>{@code resultSchemaJson} (migration V209) is the OPTIONAL, TENANT-AUTHORED schema an agent's
 * JOB results must conform to; null means prose. It is deliberately NOT the generated output
 * schema: a tenant schema stored there would be silently destroyed the next time anyone edited the
 * agent's tool list. Nothing regenerates this one.
 */
@Embeddable
public class AgentSchemas {

    @Column(name = "input_schema_json", columnDefinition = "TEXT")
    private String inputSchemaJson;

    @Column(name = "output_schema_json", columnDefinition = "TEXT")
    private String outputSchemaJson;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 0;

    @Column(name = "result_schema_json", columnDefinition = "TEXT")
    private String resultSchemaJson;

    /** JPA, and the nothing-generated starting state. */
    protected AgentSchemas() {}

    public static AgentSchemas none() {
        return new AgentSchemas();
    }

    /** Replaces the generated pair and bumps the version. */
    public AgentSchemas withGenerated(String input, String output) {
        AgentSchemas s = copy();
        s.inputSchemaJson = input;
        s.outputSchemaJson = output;
        s.schemaVersion = schemaVersion + 1;
        return s;
    }

    public AgentSchemas withResultSchema(String resultSchema) {
        AgentSchemas s = copy();
        s.resultSchemaJson = resultSchema;
        return s;
    }

    private AgentSchemas copy() {
        AgentSchemas s = new AgentSchemas();
        s.inputSchemaJson = inputSchemaJson;
        s.outputSchemaJson = outputSchemaJson;
        s.schemaVersion = schemaVersion;
        s.resultSchemaJson = resultSchemaJson;
        return s;
    }

    public String inputSchemaJson() {
        return inputSchemaJson;
    }

    public String outputSchemaJson() {
        return outputSchemaJson;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String resultSchemaJson() {
        return resultSchemaJson;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AgentSchemas a
                && schemaVersion == a.schemaVersion
                && Objects.equals(inputSchemaJson, a.inputSchemaJson)
                && Objects.equals(outputSchemaJson, a.outputSchemaJson)
                && Objects.equals(resultSchemaJson, a.resultSchemaJson);
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputSchemaJson, outputSchemaJson, schemaVersion, resultSchemaJson);
    }
}
