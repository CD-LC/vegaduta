package com.agenticai.core.agents.memory;

import java.util.List;

/**
 * Read-only view of the current turn's untrusted-content taint, as memory writes need it. The real
 * implementation is supplied by the taint package (work package B) as a bean that resolves the turn
 * in progress; this module only reads it, via an {@code ObjectProvider}.
 *
 * <p>When no bean exists, or reading it fails, {@link MemoryWriteGate} treats the turn as tainted
 * (quarantine), never as clean.
 */
public interface TurnTaintView {

    /** True once any content an outsider could have authored entered the turn's context. */
    boolean isTainted();

    /** Bounded labels of what tainted the turn (tool names or channels); empty when clean. */
    List<String> sources();
}
