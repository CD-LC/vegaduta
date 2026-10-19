package com.agenticai.core.agents.job.durability;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * Blocker 5: the re-queue is the lease. Pins the atomic guard in the UPDATE itself (a worker that heartbeated
 * after the sweep began must not be re-queued), since the database is where two replicas actually race.
 */
class AgentJobRequeueLeaseTest {

    @Test
    void theRequeueUpdateReChecksThatNoHeartbeatLandedSinceTheSweepBegan() throws Exception {
        Method m = AgentJobRepository.class.getMethod(
            "requeueIfStillRunningInternal", UUID.class, Instant.class, AgentJobStatus.class, AgentJobStatus.class);
        String jpql = m.getAnnotation(Query.class).value();
        assertTrue(jpql.contains("j.status = :from"), jpql);
        assertTrue(jpql.contains("j.heartbeatAt is null or j.heartbeatAt < :now"), jpql);
    }
}
