package com.agenticai.core.omnichannel.matrix;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** A notice the bot already sent to a room, so a one-shot message is never repeated. */
@Entity
@Table(
        name = "matrix_room_notice",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_matrix_room_notice",
                        columnNames = {"room_id", "kind"}))
public class MatrixRoomNotice {

    /** The room is end-to-end encrypted, which this channel does not support. */
    public static final String KIND_ENCRYPTED = "ENCRYPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "room_id", nullable = false, length = 255)
    private String roomId;

    @Column(nullable = false, length = 32)
    private String kind;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MatrixRoomNotice() {
        // JPA
    }

    public MatrixRoomNotice(String roomId, String kind) {
        this.roomId = roomId;
        this.kind = kind;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getKind() {
        return kind;
    }
}
