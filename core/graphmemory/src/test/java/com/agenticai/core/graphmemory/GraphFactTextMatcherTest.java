package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GraphFactTextMatcher}: whole-phrase endpoint matching, the first-person/self-entity
 * rule, and {@code edgesOnlyThisFactSupports}' "only invalidate what no surviving fact still names"
 * decision.
 */
class GraphFactTextMatcherTest {

    @Test
    void bothEndpointsMustAppearAsWholePhrases() {
        assertThat(GraphFactTextMatcher.factSupportsEdge("Priya works at Acme", "Priya", "Acme"))
                .isTrue();
        // "Acme" inside "Acmecorp" is not a whole-word hit.
        assertThat(
                        GraphFactTextMatcher.factSupportsEdge(
                                "Priya works at Acmecorp", "Priya", "Acme"))
                .isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge("Priyanka works at Acme", "Priya", "Acme"))
                .isFalse();
    }

    @Test
    void matchingIsCaseInsensitiveAndSpansPunctuation() {
        assertThat(GraphFactTextMatcher.factSupportsEdge("PRIYA left acme.", "priya", "Acme"))
                .isTrue();
    }

    @Test
    void unusableOrMissingInputsAreNotSupported() {
        assertThat(GraphFactTextMatcher.factSupportsEdge(null, "a", "b")).isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge("  ", "a", "b")).isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge("a b", null, "b")).isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge("a b", "a", " ")).isFalse();
        // A backslash in a stored name is unmatchable - refuse rather than half-match.
        assertThat(GraphFactTextMatcher.factSupportsEdge("a\\b c", "a\\b", "c")).isFalse();
    }

    @Test
    void firstPersonTextMatchesASelfEntityWhenTheOtherEndIsNamed() {
        // "User" is a self name - "I work at Acme" supports a User->Acme edge.
        assertThat(GraphFactTextMatcher.factSupportsEdge("I work at Acme", "user", "Acme"))
                .isTrue();
        assertThat(GraphFactTextMatcher.factSupportsEdge("my manager is Raj", "me", "Raj"))
                .isTrue();
        // But first-person without either end named does not.
        assertThat(GraphFactTextMatcher.factSupportsEdge("I work at Acme", "user", "Globex"))
                .isFalse();
        // And a non-self subject still has to be named outright.
        assertThat(GraphFactTextMatcher.factSupportsEdge("I work at Acme", "Priya", "Acme"))
                .isFalse();
    }

    @Test
    void edgesOnlyThisFactSupportsKeepsEdgesAnotherFactStillNames() {
        GraphFactTextMatcher.ValidEdge edge =
                new GraphFactTextMatcher.ValidEdge("Priya", "WORKS_AT", "Acme");
        GraphFactTextMatcher.ValidEdge other =
                new GraphFactTextMatcher.ValidEdge("Raj", "MANAGES", "Priya");

        // The removed text names both ends of `edge`; the surviving fact does not - invalidate it.
        assertThat(
                        GraphFactTextMatcher.edgesOnlyThisFactSupports(
                                List.of(edge, other),
                                "Priya works at Acme",
                                List.of("Raj manages Priya")))
                .containsExactly(edge);

        // If the surviving fact names the same pair, the edge stays.
        assertThat(
                        GraphFactTextMatcher.edgesOnlyThisFactSupports(
                                List.of(edge),
                                "Priya works at Acme",
                                List.of("Priya is at Acme too")))
                .isEmpty();
    }

    @Test
    void nullAndSparseInputsYieldNoInvalidations() {
        GraphFactTextMatcher.ValidEdge edge = new GraphFactTextMatcher.ValidEdge("A", "R", "B");

        assertThat(GraphFactTextMatcher.edgesOnlyThisFactSupports(null, "A R B", null)).isEmpty();
        assertThat(GraphFactTextMatcher.edgesOnlyThisFactSupports(List.of(edge), null, null))
                .isEmpty();
        // A null edge in the list is skipped, not exploded on.
        assertThat(
                        GraphFactTextMatcher.edgesOnlyThisFactSupports(
                                java.util.Arrays.asList(edge, null), "A R B", List.of()))
                .containsExactly(edge);
    }
}
