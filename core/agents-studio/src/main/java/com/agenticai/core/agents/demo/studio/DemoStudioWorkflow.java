package com.agenticai.core.agents.demo.studio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A workflow a /try visitor designed in the Agent Studio (migration V339).
 * Control-plane row keyed to the sandbox session; the definition is the
 * page's own step list ({@link DemoStudioWorkflowRunner.Definition}) stored
 * as opaque JSON. See V339's header for the design calls.
 */
@Entity
@Table(name = "demo_studio_workflow", schema = "control_plane")
public class DemoStudioWorkflow {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "created_ip", length = 45, updatable = false)
    private String createdIp;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "definition_json", nullable = false, columnDefinition = "text")
    private String definitionJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DemoStudioWorkflow() {
    }

    public DemoStudioWorkflow(UUID sessionId, String createdIp, String name, String definitionJson, Instant now) {
        this.sessionId = sessionId;
        this.createdIp = createdIp;
        this.name = name;
        this.definitionJson = definitionJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String definitionJson, Instant now) {
        this.name = name;
        this.definitionJson = definitionJson;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getCreatedIp() {
        return createdIp;
    }

    public String getName() {
        return name;
    }

    public String getDefinitionJson() {
        return definitionJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
