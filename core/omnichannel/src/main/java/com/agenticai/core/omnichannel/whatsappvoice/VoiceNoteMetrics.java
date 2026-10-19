package com.agenticai.core.omnichannel.whatsappvoice;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One counter per {@link VoiceNoteOutcome}: {@code whatsapp.voice_note{outcome=...}} on the Micrometer
 * registry when there is one, plus plain in-process tallies (always on) so the numbers are readable in
 * tests and never depend on a registry being wired. No tenant, sender or content tags - cardinality is
 * the number of outcomes, nothing else.
 */
final class VoiceNoteMetrics {

    static final String METRIC = "whatsapp.voice_note";

    private final MeterRegistry registry;
    private final Map<VoiceNoteOutcome, AtomicLong> tallies = new EnumMap<>(VoiceNoteOutcome.class);

    VoiceNoteMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (VoiceNoteOutcome outcome : VoiceNoteOutcome.values()) {
            tallies.put(outcome, new AtomicLong());
        }
    }

    void count(VoiceNoteOutcome outcome) {
        tallies.get(outcome).incrementAndGet();
        if (registry != null) {
            try {
                registry.counter(METRIC, "outcome", outcome.name().toLowerCase(Locale.ROOT)).increment();
            } catch (RuntimeException ignored) {
                // A metrics hiccup must never break a customer's voice note.
            }
        }
    }

    long tally(VoiceNoteOutcome outcome) {
        return tallies.get(outcome).get();
    }
}
