package com.agenticai.core.agents.trust;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DelegationTokenRepository extends JpaRepository<DelegationTokenRecord, String> {

    List<DelegationTokenRecord> findByGrantIdOrderByIssuedAtDesc(UUID grantId);

    /** Revocation cascade: every live token under a grant dies with the grant. */
    @Modifying
    @Query("UPDATE DelegationTokenRecord t SET t.status = 'REVOKED' "
        + "WHERE t.grantId = :grantId AND t.status = 'ACTIVE'")
    int revokeAllForGrant(@Param("grantId") UUID grantId);
}
