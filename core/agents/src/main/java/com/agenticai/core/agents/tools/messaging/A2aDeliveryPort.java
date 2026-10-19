package com.agenticai.core.agents.tools.messaging;

/**
 * Deliver a text to an external A2A-speaking agent (the "A2A bridge" channel): a scheduled task or
 * any other producer can hand its result to a remote agent - for example an agent gateway fronting
 * chat apps - by its signed agent-card URL.
 *
 * <p>Kernel-side port like {@link ChannelSendPort}: the implementation lives in {@code
 * agents-interop} on top of the outbound A2A client ({@code A2aClientService}), so every send is
 * subject to the operator allow-list ({@code app.a2a.client.allowed-hosts}), the SSRF guard and the
 * signed-card check - the producer never talks to the remote directly.
 */
public interface A2aDeliveryPort {

    /**
     * @param cardUrl the remote agent's card URL (the same {@code agent_card_url} the {@code
     *     a2a_call_agent} tool takes).
     * @param message the text to deliver.
     * @param requestedBy attribution, e.g. {@code "scheduled-task:<uuid>"} - never a model-supplied
     *     name.
     * @return the outcome: {@code delivered=true} when the remote completed the task; {@code detail}
     *     is a short human-readable line (remote state or refusal reason).
     */
    A2aSendResult send(String cardUrl, String message, String requestedBy);

    record A2aSendResult(boolean delivered, String detail) {}
}
