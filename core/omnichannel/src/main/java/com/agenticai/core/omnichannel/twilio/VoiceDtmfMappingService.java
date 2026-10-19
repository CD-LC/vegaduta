package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.twilio.dto.VoiceDtmfMappingResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master Roadmap 2026 item #17 fast-follow (voice DTMF handling) - CRUD over
 * {@link VoiceDtmfMapping} plus the one read {@link VoiceRelayWebSocketHandler}
 * actually needs mid-call ({@link #findAgentIdForDigit}). See {@link
 * VoiceDtmfMapping}'s javadoc for the full design rationale (per-call agent
 * switch, not a change to the number's default agent).
 */
@Service
public class VoiceDtmfMappingService {

    // Twilio's own DTMF digit set - see Conversation Relay's `dtmf` message
    // docs (the same "digit" field VoiceRelayWebSocketHandler reads).
    private static final Pattern VALID_DIGIT = Pattern.compile("^[0-9*#]$");

    private final VoiceDtmfMappingRepository repository;
    private final AgentRepository agentRepository;

    public VoiceDtmfMappingService(VoiceDtmfMappingRepository repository, AgentRepository agentRepository) {
        this.repository = repository;
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public List<VoiceDtmfMappingResponse> list() {
        return repository.findAllByOrderByDigitAsc().stream()
            .map(m -> VoiceDtmfMappingResponse.from(m, agentNameOrUnknown(m.getAgentId())))
            .toList();
    }

    @Transactional
    public VoiceDtmfMappingResponse upsert(String digit, UUID agentId, String label) {
        String normalized = normalizeDigit(digit);
        Agent agent = agentRepository.findById(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
        VoiceDtmfMapping mapping = repository.findByDigit(normalized).orElse(null);
        if (mapping == null) {
            mapping = repository.save(new VoiceDtmfMapping(normalized, agentId, label));
        } else {
            mapping.update(agentId, label);
        }
        return VoiceDtmfMappingResponse.from(mapping, agent.getName());
    }

    @Transactional
    public void delete(String digit) {
        String normalized = normalizeDigit(digit);
        if (repository.findByDigit(normalized).isEmpty()) {
            throw new VoiceDtmfMappingNotFoundException(normalized);
        }
        repository.deleteByDigit(normalized);
    }

    /** {@link VoiceRelayWebSocketHandler}'s one hot-path read - empty when the digit has no configured mapping (the ordinary case; the caller just keeps talking to whichever agent already answered). */
    @Transactional(readOnly = true)
    public Optional<UUID> findAgentIdForDigit(String digit) {
        if (digit == null || !VALID_DIGIT.matcher(digit).matches()) {
            return Optional.empty();
        }
        return repository.findByDigit(digit).map(VoiceDtmfMapping::getAgentId);
    }

    private String agentNameOrUnknown(UUID agentId) {
        return agentRepository.findById(agentId).map(Agent::getName).orElse("(deleted agent)");
    }

    private static String normalizeDigit(String digit) {
        if (digit == null || !VALID_DIGIT.matcher(digit).matches()) {
            throw new IllegalArgumentException("digit must be a single character: 0-9, *, or #");
        }
        return digit;
    }
}
