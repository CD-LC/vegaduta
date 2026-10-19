package com.agenticai.core.quality.simulation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One tenant-authored CUSTOM adversarial persona (migration V266). Built-in
 * personas (prompt injection, jailbreak, exfiltration, phishing, off-brand
 * bait, abuse, scope creep, policy abuse) are a static list in {@link
 * SimulationPersonaService} and never have a row here - see that class's
 * javadoc for why (and for how the two namespaces merge).
 */
@Entity
@Table(name = "simulation_persona")
public class SimulationPersona {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String key;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String objective;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SimulationPersona() {
        // JPA
    }

    public SimulationPersona(String key, String name, String description, String objective, String createdBy) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.objective = objective;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getObjective() {
        return objective;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
