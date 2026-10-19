package com.agenticai.core.omnichannel.session;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of every channel's "route inbound to this agent" endpoint ({@code PUT .../default-agent}).
 */
public record SetDefaultAgentRequest(@NotNull UUID agentId) {}
