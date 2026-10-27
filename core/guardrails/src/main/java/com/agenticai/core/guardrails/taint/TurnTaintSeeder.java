package com.agenticai.core.guardrails.taint;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Port for marking a turn tainted from OUTSIDE the tool-call path: the chat turn's own prompt
 * assembly, a workflow knowledge node, an inbound channel read. Implemented by the taint layer's
 * owner (AgentTaintPolicy); a null/absent seeder (layer off, slim test context) means "nothing to
 * mark" - exactly the pre-taint behaviour.
 */
@FunctionalInterface
public interface TurnTaintSeeder {

    /**
     * Marks the turn identified by {@code turnKey} as tainted by {@code source}, if the taint layer
     * is active. No-op when the layer is off or the source is blank.
     */
    void seed(AtomicInteger turnKey, String source);
}
