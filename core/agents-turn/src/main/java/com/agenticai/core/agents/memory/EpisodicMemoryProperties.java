package com.agenticai.core.agents.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.memory.episodic.*}: the platform level of the episodic write path (ADR-0028 WP4b). Off by default; a
 * tenant or an agent can only switch it off further ({@code memory.episodic.enabled}). The recorder makes no model
 * call: an episode is a template over verified signals, filed as a PENDING or QUARANTINED proposal.
 */
@ConfigurationProperties(prefix = "app.memory.episodic")
public class EpisodicMemoryProperties {

    /** Platform switch: off means no episode is built or proposed. */
    private boolean enabled = false;

    /** Most episodes proposed per agent in a rolling hour. */
    private int maxPerAgentPerHour = 30;

    /** Most episodes proposed per agent and user in a rolling hour. */
    private int maxPerUserPerHour = 10;

    /** Most episodes proposed per agent in a rolling day. */
    private int maxPerAgentPerDay = 200;

    /** Hard cap on the characters of one episode. */
    private int maxChars = 600;

    /** Episodes waiting for the background worker; a full queue drops the episode. */
    private int queueSize = 16;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxPerAgentPerHour() {
        return maxPerAgentPerHour;
    }

    public void setMaxPerAgentPerHour(int v) {
        this.maxPerAgentPerHour = Math.max(0, v);
    }

    public int getMaxPerUserPerHour() {
        return maxPerUserPerHour;
    }

    public void setMaxPerUserPerHour(int v) {
        this.maxPerUserPerHour = Math.max(0, v);
    }

    public int getMaxPerAgentPerDay() {
        return maxPerAgentPerDay;
    }

    public void setMaxPerAgentPerDay(int v) {
        this.maxPerAgentPerDay = Math.max(0, v);
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int v) {
        this.maxChars = Math.max(80, Math.min(v, 2_000));
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(int v) {
        this.queueSize = Math.max(1, v);
    }
}
