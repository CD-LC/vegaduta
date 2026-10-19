package com.agenticai.core.omnichannel.matrix;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface MatrixRoomNoticeRepository extends JpaRepository<MatrixRoomNotice, UUID> {

    /**
     * Records the notice unless it exists. The unique key makes this race-free: of two concurrent
     * callers exactly one sees 1.
     *
     * @return 1 when this call recorded it (the caller should send the notice), 0 when it was
     *     already there
     */
    @Transactional
    @Modifying
    @Query(
            value =
                    "INSERT INTO matrix_room_notice (id, room_id, kind, created_at) "
                            + "VALUES (gen_random_uuid(), :roomId, :kind, now()) "
                            + "ON CONFLICT (room_id, kind) DO NOTHING",
            nativeQuery = true)
    int recordIfAbsent(@Param("roomId") String roomId, @Param("kind") String kind);
}
