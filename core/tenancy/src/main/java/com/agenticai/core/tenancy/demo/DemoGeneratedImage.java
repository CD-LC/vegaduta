package com.agenticai.core.tenancy.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One image an anonymous sandbox visitor generated (migration V127) - the
 * control-plane pointer to bytes that live in S3/MinIO, never in Postgres.
 *
 * <h2>Why this is not {@code com.agenticai.core.imagegen.GeneratedImage}</h2>
 * That entity is unschema'd - i.e. it lands in whatever per-tenant schema
 * {@code TenantContext} currently names - and is served by an
 * <em>authenticated</em> controller that relies on exactly that scoping for
 * its access control. Neither property survives contact with this feature:
 *
 * <ul>
 *   <li><b>A browser cannot put {@code X-Demo-Token} on an {@code <img src>}.</b>
 *       The whole demo surface is identified by that header, and the one
 *       request the visitor's browser makes on its own - fetching the image -
 *       is the one request that cannot carry it. So the image URL has to be a
 *       <b>capability URL</b>: {@link #id} is a random v4 UUID and possession
 *       of it IS the credential, the same unguessable-identifier posture
 *       {@code WorkflowDefinition}'s webhook path already takes.</li>
 *   <li>Serving from a capability URL means resolving {@code id -> objectKey}
 *       with <b>no tenant context at all</b> - there is no token to derive a
 *       schema from. That lookup can only happen in {@code control_plane},
 *       which is exactly where {@link DemoSession} already lives and for the
 *       same chicken-and-egg reason.</li>
 * </ul>
 *
 * <p>What this deliberately does NOT store is the image bytes. Same split as
 * {@code GeneratedImage} and {@code SarvamGeneratedAudio}: bytes to object
 * storage, a key here.
 *
 * <p><b>Retention, stated honestly:</b> nothing purges these rows or their
 * objects today. {@code DemoSessionExpiryJob} suspends a lapsed sandbox's
 * tenant but deliberately does not reclaim disk (see its javadoc), and this
 * follows that same call rather than inventing a different one - the
 * {@code created_at} index exists so a purge sweep is a cheap thing to add
 * when someone decides on the retention window.
 */
@Entity
@Table(name = "demo_generated_image", schema = "control_plane")
public class DemoGeneratedImage {

    /** Random v4 (never a sequence) - see the class javadoc: this value is the capability credential in the public URL. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "object_key", nullable = false, length = 512, updatable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 128, updatable = false)
    private String contentType;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String prompt;

    @Column(name = "model", nullable = false, length = 128, updatable = false)
    private String model;

    /** Abuse forensics only - never returned to any caller. */
    @Column(name = "created_ip", length = 45, updatable = false)
    private String createdIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DemoGeneratedImage() {
        // JPA
    }

    public DemoGeneratedImage(
        UUID sessionId, String objectKey, String contentType, String prompt, String model, String createdIp
    ) {
        this.sessionId = sessionId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.prompt = prompt;
        this.model = model;
        this.createdIp = createdIp;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getModel() {
        return model;
    }

    public String getCreatedIp() {
        return createdIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
