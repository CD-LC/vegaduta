package com.agenticai.core.agents.vision;

/**
 * How a vision session captures frames (shared live-vision contract):
 * {@link #SNAPSHOT} is one-off user-gesture frames (the "show the agent"
 * button - every plan, frame-quota only), {@link #WATCH} is client-timed
 * interval capture with change gating, {@link #LIVE} is a true streaming
 * session (LiveKit, Program S1). WATCH and LIVE are the continuous modes:
 * both require the plan's {@code live_vision_allowed} entitlement and are
 * minute-metered on top of the frame quota.
 */
public enum VisionSessionMode {
    SNAPSHOT,
    WATCH,
    LIVE;

    /** The privacy/cost-sensitive continuous modes - entitlement- and minute-gated. */
    public boolean isContinuous() {
        return this == WATCH || this == LIVE;
    }
}
