package com.agenticai.core.agents.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GenerationParams} is the embedded value object behind {@link Agent}'s flat
 * maxTokens/temperature/topP/sampling accessors. These pin the copy-on-write contract (a
 * {@code with...} never mutates the receiver) and the null-means-provider-default shape of the
 * optional sampling controls.
 */
class GenerationParamsTest {

    @Test
    void ofSeedsOnlyTheCoreControls() {
        GenerationParams p = GenerationParams.of(1024, 0.7, 1.0);

        assertEquals(1024, p.getMaxTokens());
        assertEquals(0.7, p.getTemperature());
        assertEquals(1.0, p.getTopP());
        assertNull(p.getTopK());
        assertTrue(p.getStopSequences().isEmpty());
        assertNull(p.getFrequencyPenalty());
        assertNull(p.getPresencePenalty());
        assertNull(p.getSeed());
    }

    @Test
    void withCoreKeepsTheSamplingControlsAndMutatesNothing() {
        GenerationParams p =
            GenerationParams.of(1024, 0.7, 1.0).withSampling(40, List.of("END"), 0.5, 0.25, 9);

        GenerationParams changed = p.withCore(512, 0.1, 0.9);

        assertNotSame(p, changed);
        assertEquals(512, changed.getMaxTokens());
        assertEquals(0.1, changed.getTemperature());
        assertEquals(0.9, changed.getTopP());
        // Sampling controls survived the core change.
        assertEquals(40, changed.getTopK());
        assertEquals(List.of("END"), changed.getStopSequences());
        assertEquals(0.5, changed.getFrequencyPenalty());
        assertEquals(0.25, changed.getPresencePenalty());
        assertEquals(9, changed.getSeed());
        // The original is untouched.
        assertEquals(1024, p.getMaxTokens());
        assertEquals(0.7, p.getTemperature());
    }

    @Test
    void withSamplingDefensivelyCopiesAndNullClears() {
        java.util.ArrayList<String> stops = new java.util.ArrayList<>(List.of("A", "B"));
        GenerationParams p = GenerationParams.of(1, 0, 0).withSampling(10, stops, null, null, null);
        stops.add("C");

        assertEquals(List.of("A", "B"), p.getStopSequences());

        GenerationParams cleared = p.withSampling(null, null, null, null, null);
        assertNull(cleared.getTopK());
        assertTrue(cleared.getStopSequences().isEmpty());
    }

    @Test
    void stopSequencesGetterReturnsAnImmutableCopy() {
        GenerationParams p = GenerationParams.of(1, 0, 0).withSampling(null, List.of("X"), null, null, null);
        assertThrows(UnsupportedOperationException.class, () -> p.getStopSequences().add("Y"));
    }

    @Test
    void equalityCoversAllFields() {
        GenerationParams a = GenerationParams.of(10, 0.2, 0.8).withSampling(5, List.of("S"), 0.1, 0.2, 3);
        GenerationParams b = GenerationParams.of(10, 0.2, 0.8).withSampling(5, List.of("S"), 0.1, 0.2, 3);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, GenerationParams.of(10, 0.2, 0.8));
        assertNotEquals(a, a.withCore(10, 0.2, 0.9));
        assertNotEquals(a, a.withSampling(6, List.of("S"), 0.1, 0.2, 3));
    }
}
