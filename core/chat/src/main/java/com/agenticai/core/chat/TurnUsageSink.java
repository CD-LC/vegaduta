package com.agenticai.core.chat;

/** Feeds each finished call to the {@link TurnUsageCounter}, when one is installed. */
final class TurnUsageSink implements LlmCallSink {

    @Override
    public void onCall(LlmCallObservation obs) {
        TurnUsageCounter counter = TurnUsageCounter.active();
        if (counter == null) {
            return;
        }
        counter.record(
                obs.attribution().sessionId(),
                obs.tokens().prompt(),
                obs.tokens().completion(),
                obs.tokens().total(),
                obs.success(),
                obs.latencyMs(),
                obs.startedAt());
    }
}
