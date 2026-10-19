package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.twilio.dto.VoiceNumberAgentResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over {@link VoiceNumberAgent} plus the one read {@link
 * VoiceRelayWebSocketHandler} needs when a call is set up ({@link
 * #findAgentIdForNumber}). See {@link VoiceNumberAgent}'s javadoc for the
 * design rationale (per-call override, agent precedence, and why a
 * tenant-wide default agent is still required).
 *
 * <p>Deliberately mirrors {@link VoiceDtmfMappingService} method for method -
 * these two are the same kind of per-tenant voice routing table and a reader
 * who knows one should not have to re-learn the other.
 */
@Service
public class VoiceNumberAgentService {

    /**
     * E.164: a leading {@code +}, a non-zero country-code digit, then 7-15
     * digits in total. Twilio always delivers {@code To}/{@code From} in this
     * form, so this is both the storage format and the validation rule.
     */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");

    /** Everything an admin might type as separators - {@code "+1 (555) 123-4567"} and {@code "+15551234567"} must reach the same row. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s()\\-.]");

    private final VoiceNumberAgentRepository repository;
    private final AgentRepository agentRepository;

    public VoiceNumberAgentService(VoiceNumberAgentRepository repository, AgentRepository agentRepository) {
        this.repository = repository;
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public List<VoiceNumberAgentResponse> list() {
        return repository.findAllByOrderByPhoneNumberAsc().stream()
            .map(m -> VoiceNumberAgentResponse.from(m, agentNameOrUnknown(m.getAgentId())))
            .toList();
    }

    @Transactional
    public VoiceNumberAgentResponse upsert(String phoneNumber, UUID agentId, String label) {
        String normalized = normalizeNumber(phoneNumber);
        Agent agent = agentRepository.findById(agentId).orElseThrow(() -> new AgentNotFoundException(agentId));
        VoiceNumberAgent mapping = repository.findByPhoneNumber(normalized).orElse(null);
        if (mapping == null) {
            mapping = repository.save(new VoiceNumberAgent(normalized, agentId, label));
        } else {
            mapping.update(agentId, label);
        }
        return VoiceNumberAgentResponse.from(mapping, agent.getName());
    }

    @Transactional
    public void delete(String phoneNumber) {
        String normalized = normalizeNumber(phoneNumber);
        if (repository.findByPhoneNumber(normalized).isEmpty()) {
            throw new VoiceNumberAgentNotFoundException(normalized);
        }
        repository.deleteByPhoneNumber(normalized);
    }

    /**
     * {@link VoiceRelayWebSocketHandler}'s one read, once per inbound call.
     *
     * <p>Returns empty rather than throwing for anything unrecognized - an
     * unmapped number is the ordinary case (the tenant's default agent
     * answers, exactly as it did before this feature existed), and the input
     * reaching this method is <b>caller-influenceable</b>: see that class's
     * {@code resolveDialedNumber} for the trust analysis. Empty must
     * therefore be a quiet, normal outcome and never an error path.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findAgentIdForNumber(String phoneNumber) {
        String normalized = normalizeQuietly(phoneNumber);
        if (normalized == null) {
            return Optional.empty();
        }
        return repository.findByPhoneNumber(normalized).map(VoiceNumberAgent::getAgentId);
    }

    private String agentNameOrUnknown(UUID agentId) {
        return agentRepository.findById(agentId).map(Agent::getName).orElse("(deleted agent)");
    }

    /** Admin-facing: an unusable number is a 400, not a silently-ignored write. */
    static String normalizeNumber(String phoneNumber) {
        String normalized = normalizeQuietly(phoneNumber);
        if (normalized == null) {
            throw new IllegalArgumentException(
                "phoneNumber must be in E.164 format - a leading '+', country code, then 7-15 digits (e.g. +15551234567)"
            );
        }
        return normalized;
    }

    /** Shared normalization; {@code null} means "not a usable E.164 number" so each caller can decide whether that is a 400 or a no-op. */
    private static String normalizeQuietly(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        String stripped = SEPARATORS.matcher(phoneNumber.trim()).replaceAll("");
        return E164.matcher(stripped).matches() ? stripped : null;
    }
}
