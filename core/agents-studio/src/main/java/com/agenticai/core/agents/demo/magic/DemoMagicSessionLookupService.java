package com.agenticai.core.agents.demo.magic;

import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sandbox-session lookups for {@link DemoMagicController}, so the controller never touches {@link
 * DemoSessionRepository} directly.
 */
@Service
public class DemoMagicSessionLookupService {

    private final DemoSessionRepository demoSessionRepository;

    public DemoMagicSessionLookupService(DemoSessionRepository demoSessionRepository) {
        this.demoSessionRepository = demoSessionRepository;
    }

    @Transactional(readOnly = true)
    public Optional<DemoSession> findById(UUID sessionId) {
        return demoSessionRepository.findById(sessionId);
    }
}
