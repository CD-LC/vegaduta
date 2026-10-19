package com.agenticai.core.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * ref to real entity id ({@code brief_binding}, pk brief_id + ref): what {@code a1} / {@code k1}
 * became once the apply engine created it. Written by the apply engine (a later layer); readiness
 * reads it to report the real knowledge collection's contents.
 */
@Entity
@Table(name = "brief_binding")
@IdClass(BriefBindingEntity.Key.class)
public class BriefBindingEntity {

    @Id
    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Id
    @Column(nullable = false, length = 32)
    private String ref;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    protected BriefBindingEntity() {
        // JPA
    }

    public BriefBindingEntity(UUID briefId, String ref, String kind, String entityId) {
        this.briefId = briefId;
        this.ref = ref;
        this.kind = kind;
        this.entityId = entityId;
    }

    public UUID getBriefId() {
        return briefId;
    }

    public String getRef() {
        return ref;
    }

    public String getKind() {
        return kind;
    }

    public String getEntityId() {
        return entityId;
    }

    /** Composite primary key (brief_id, ref). */
    public static class Key implements Serializable {
        private UUID briefId;
        private String ref;

        public Key() {
        }

        public Key(UUID briefId, String ref) {
            this.briefId = briefId;
            this.ref = ref;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(briefId, k.briefId) && Objects.equals(ref, k.ref);
        }

        @Override
        public int hashCode() {
            return Objects.hash(briefId, ref);
        }
    }
}
