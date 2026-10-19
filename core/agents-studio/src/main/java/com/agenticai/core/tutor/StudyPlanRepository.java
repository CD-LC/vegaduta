package com.agenticai.core.tutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every member-facing read is owner-scoped; only the reminder sweep reads across owners - and it
 * reads the review deck alone, never the (large) curriculum document.
 */
public interface StudyPlanRepository extends JpaRepository<StudyPlan, UUID> {

    /** Keyset start for {@link #findReviewDecksAfter}: sorts before every real id. */
    String FIRST_PAGE = "00000000-0000-0000-0000-000000000000";

    List<StudyPlan> findByOwnerSubjectOrderByUpdatedAtDesc(String ownerSubject);

    Optional<StudyPlan> findByIdAndOwnerSubject(UUID id, String ownerSubject);

    long countByOwnerSubject(String ownerSubject);

    /**
     * Reminder sweep, one keyset page: plans (ANY status - a finished course still has cards coming
     * due) whose deck holds at least one card. Columns, all text: {@code [0]} id, {@code [1]}
     * owner_subject, {@code [2]} topic, {@code [3]} review JSON. The curriculum column is never
     * selected, so a tick costs the decks, not the courses. {@code review -> 'cards' -> 0} is NULL
     * (no error) for an empty, missing or non-array {@code cards}.
     *
     * @param afterId the last id of the previous page, or {@link #FIRST_PAGE}
     */
    @Query(
        value = """
            SELECT CAST(id AS text), owner_subject, topic, CAST(review AS text)
              FROM study_plan
             WHERE id > CAST(:afterId AS uuid)
               AND (review -> 'cards' -> 0) IS NOT NULL
             ORDER BY id
             LIMIT :pageSize
            """,
        nativeQuery = true)
    List<Object[]> findReviewDecksAfter(@Param("afterId") String afterId, @Param("pageSize") int pageSize);

    /**
     * One member's non-empty decks, without the curriculum. Columns, all text: {@code [0]} id,
     * {@code [1]} review JSON. A member holds at most {@code app.tutor.max-plans-per-user} plans.
     */
    @Query(
        value = """
            SELECT CAST(id AS text), CAST(review AS text)
              FROM study_plan
             WHERE owner_subject = :ownerSubject
               AND (review -> 'cards' -> 0) IS NOT NULL
            """,
        nativeQuery = true)
    List<Object[]> findReviewDecksOfOwner(@Param("ownerSubject") String ownerSubject);

    /**
     * Stamps {@code review.lastRemindedAt} in place - no entity load, no curriculum rewrite, and
     * {@code updated_at} is left alone (the course list sorts on it). The version is bumped so a
     * learner's write that read the row before the stamp fails its optimistic check instead of
     * silently erasing the stamp.
     */
    @Modifying
    @Query(
        value = """
            UPDATE study_plan
               SET review = jsonb_set(review, '{lastRemindedAt}', to_jsonb(CAST(:stamp AS text)), true),
                   version = version + 1
             WHERE id = CAST(:id AS uuid)
            """,
        nativeQuery = true)
    int stampReminded(@Param("id") String id, @Param("stamp") String stamp);

    long deleteByOwnerSubject(String ownerSubject);
}
