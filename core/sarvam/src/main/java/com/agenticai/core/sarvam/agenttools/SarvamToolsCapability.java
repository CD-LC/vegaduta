package com.agenticai.core.sarvam.agenttools;

import com.agenticai.core.agents.capability.AgentCapability;
import com.agenticai.core.agents.capability.CapabilityAvailability;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.sarvam.SarvamCredentialService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Sarvam translate/transliterate/identify-language/speak tools - the capability-ized form of the
 * old {@code sarvam_enabled} column + {@code PATCH /api/agents/{id}/sarvam} endpoint. Column-backed
 * on purpose (see {@code AgentCapabilityState}'s javadoc). Lives with the Sarvam adapters because
 * its availability reads the Sarvam credential (ADR-0011 rule a); runtime resolution still lives in
 * {@code AgentMediaToolResolver#resolveSarvamTools}, which keeps the same "tenant credential ACTIVE
 * + this per-agent flag" double gate.
 */
@Component
public class SarvamToolsCapability implements AgentCapability {

    private final ObjectProvider<SarvamCredentialService> sarvamCredentialService;

    public SarvamToolsCapability(ObjectProvider<SarvamCredentialService> sarvamCredentialService) {
        this.sarvamCredentialService = sarvamCredentialService;
    }

    @Override
    public String key() {
        return "sarvam-tools";
    }

    @Override
    public String label() {
        return "Sarvam tools";
    }

    @Override
    public String description() {
        return "Translation, transliteration, language detection and Indian-language speech tools.";
    }

    @Override
    public String category() {
        return "tools";
    }

    @Override
    public CapabilityAvailability availability() {
        SarvamCredentialService service = sarvamCredentialService.getIfAvailable();
        return service != null && service.isActive()
                ? CapabilityAvailability.AVAILABLE
                : CapabilityAvailability.NEEDS_SETUP;
    }

    @Override
    public String availabilityHint() {
        return "Activate a Sarvam AI key on the Providers page - the toggle only attaches the tools once that is set up.";
    }

    @Override
    public boolean isEnabled(Agent agent) {
        return agent.isSarvamEnabled();
    }

    @Override
    public void apply(Agent agent, boolean enabled) {
        agent.setSarvamEnabled(enabled);
    }
}
