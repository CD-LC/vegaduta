package com.agenticai.core.providers;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformHostedLlmProviderRepository extends JpaRepository<PlatformHostedLlmProvider, Long> {

    List<PlatformHostedLlmProvider> findAllByOrderByPriorityAsc();

    /**
     * The pool {@code FreeTierProviderSelector} actually picks from - lowest
     * {@code priority} first, so the first element is always the one to try.
     */
    List<PlatformHostedLlmProvider> findAllByEnabledTrueOrderByPriorityAsc();

    /** Cheap existence probe for HostedPoolAvailabilityService (no row load, no decrypt). */
    boolean existsByEnabledTrue();
}
