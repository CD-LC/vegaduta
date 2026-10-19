package com.agenticai.core.sdlc.harness;

/**
 * Cost guard for the harness. core/sdlc cannot depend on core/agents, so the
 * agents module adapts its per-run {@code RunBudget} to this interface.
 */
public interface HarnessBudget {

    boolean exhausted();

    /** Records one model round trip (text in, text out) for estimation. */
    void record(String input, String output);

    String stopMessage();

    /** A budget that never stops the run (no cap configured). */
    HarnessBudget UNLIMITED = new HarnessBudget() {
        @Override
        public boolean exhausted() {
            return false;
        }

        @Override
        public void record(String input, String output) {
        }

        @Override
        public String stopMessage() {
            return "";
        }
    };
}
