package com.agenticai.core.agents.version;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads over {@code version_label_event} (migration V342). Query-method
 * only - writes are plain {@code save} calls made by the version services
 * inside the same transaction as the label move they record.
 */
public interface VersionLabelEventRepository extends JpaRepository<VersionLabelEvent, UUID> {

    /** Label history for one entity, newest first - the audit view and the rollback lookup source. */
    List<VersionLabelEvent> findByEntityTypeAndEntityIdOrderByAppliedAtDesc(VersionEntityType entityType, UUID entityId);
}
