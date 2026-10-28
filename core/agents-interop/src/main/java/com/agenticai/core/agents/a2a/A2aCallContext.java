package com.agenticai.core.agents.a2a;

/**
 * What an A2A request says about where it sits in a chain of agent calls. Immutable and passed
 * explicitly (never kept in a ThreadLocal): the inbound controller builds it, {@link A2aTaskService}
 * parks it in the {@link A2aHopRegistry} for the duration of the turn, and the outbound tool reads
 * it back to number the next call.
 *
 * @param hop the hop number the request carried: 0 for a call that did not come from another
 *     agent, 1 for the first agent-to-agent call, and so on
 * @param traceId correlation id of the originating request, carried unchanged along the chain;
 *     never null
 * @param signed true when the request carried a message signature that verified
 */
public record A2aCallContext(int hop, String traceId, boolean signed) {

    public A2aCallContext {
        if (hop < 0) {
            throw new IllegalArgumentException("hop must not be negative");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
    }

    /** The hop number an outbound call made while serving this request carries. */
    public int nextHop() {
        return hop + 1;
    }

    /** A request that did not come from another agent. */
    public static A2aCallContext root(String traceId) {
        return new A2aCallContext(0, traceId, false);
    }
}
