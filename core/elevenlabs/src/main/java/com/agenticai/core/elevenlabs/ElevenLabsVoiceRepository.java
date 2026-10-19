package com.agenticai.core.elevenlabs;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ElevenLabsVoiceRepository extends JpaRepository<ElevenLabsVoice, UUID> {

    List<ElevenLabsVoice> findAllByOrderByCreatedAtDesc();

    // AgentToolProvider#resolveElevenLabsTools's tenant-level gate - "has this
    // tenant cloned at least one voice yet" is just repository.count() > 0
    // (inherited from JpaRepository), no custom query needed.
}
