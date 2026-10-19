package com.agenticai.core.omnichannel.handoff;

import com.agenticai.core.agents.tools.messaging.HumanHandoffPort;
import org.springframework.stereotype.Component;

/**
 * The {@code core/omnichannel} side of {@link HumanHandoffPort}'s cross- module inversion - same
 * pattern as {@code ChannelMediaSendPortImpl}/ {@code ChannelMessageQueryPortImpl} (see those
 * classes' javadoc). Exactly the one-line delegate {@link
 * HandoffEscalationService#requestHumanForAgentSession}'s own javadoc predicted this class would
 * be: that method's shape (never throws, returns a model-safe result) already matches this port's
 * contract field-for-field, so there is no adaptation logic here beyond the record type itself.
 */
@Component
public class HumanHandoffPortImpl implements HumanHandoffPort {

    private final HandoffEscalationService handoffEscalationService;

    public HumanHandoffPortImpl(HandoffEscalationService handoffEscalationService) {
        this.handoffEscalationService = handoffEscalationService;
    }

    @Override
    public HandoffOutcome requestHuman(String agentSessionId, String reason) {
        HandoffEscalationService.HandoffResult result =
                handoffEscalationService.requestHumanForAgentSession(agentSessionId, reason);
        return new HandoffOutcome(result.escalated(), result.detail());
    }
}
