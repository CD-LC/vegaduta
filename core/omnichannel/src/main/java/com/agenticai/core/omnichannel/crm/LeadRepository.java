package com.agenticai.core.omnichannel.crm;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface LeadRepository extends JpaRepository<Lead, UUID>, JpaSpecificationExecutor<Lead> {

    /** Email is stored lower-cased; callers pass {@link Lead#normalizeEmail}. */
    Optional<Lead> findByEmail(String email);

    /** Rows of (stage, count, sum(value)) - stages with no leads are absent. */
    @Query("select l.stage, count(l), coalesce(sum(l.value), 0) from Lead l group by l.stage")
    List<Object[]> pipelineTotals();
}
