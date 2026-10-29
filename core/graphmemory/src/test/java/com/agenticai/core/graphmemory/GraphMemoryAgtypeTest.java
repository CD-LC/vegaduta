package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link GraphMemoryAgtype}'s text parsing of Apache AGE's agtype values: integer scalars,
 * quoted string scalars and whole paths (vertex/edge annotations stripped, edges resolved to facts
 * by their start/end vertex names, temporal validity gating the whole path).
 */
class GraphMemoryAgtypeTest {

    // ---- parseAgtypeInt ----------------------------------------------------

    @Test
    void integerScalarsParseOutOfTheAgtypeNoise() {
        assertThat(GraphMemoryAgtype.parseAgtypeInt("3")).isEqualTo(3);
        assertThat(GraphMemoryAgtype.parseAgtypeInt("  42 ")).isEqualTo(42);
        assertThat(GraphMemoryAgtype.parseAgtypeInt("-7")).isEqualTo(-7);
        assertThat(GraphMemoryAgtype.parseAgtypeInt("count=12 rows")).isEqualTo(12);
    }

    @Test
    void nullOrDigitFreeScalarsReadAsZero() {
        assertThat(GraphMemoryAgtype.parseAgtypeInt(null)).isZero();
        assertThat(GraphMemoryAgtype.parseAgtypeInt("")).isZero();
        assertThat(GraphMemoryAgtype.parseAgtypeInt("\"abc\"")).isZero();
    }

    // ---- stripAgtypeQuotes --------------------------------------------------

    @Test
    void quotedScalarsLoseTheirOuterQuotes() {
        assertThat(GraphMemoryAgtype.stripAgtypeQuotes("\"Priya\"")).isEqualTo("Priya");
        assertThat(GraphMemoryAgtype.stripAgtypeQuotes("unquoted")).isEqualTo("unquoted");
        assertThat(GraphMemoryAgtype.stripAgtypeQuotes("\"a\"")).isEqualTo("a");
    }

    @Test
    void nullBlankAndOneCharScalarsSurviveUnchanged() {
        assertThat(GraphMemoryAgtype.stripAgtypeQuotes(null)).isEmpty();
        assertThat(GraphMemoryAgtype.stripAgtypeQuotes("  ")).isEmpty();
        assertThat(GraphMemoryAgtype.stripAgtypeQuotes("x")).isEqualTo("x");
        // Mismatched ends are not a quoted scalar.
        assertThat(GraphMemoryAgtype.stripAgtypeQuotes("\"abc")).isEqualTo("\"abc");
    }

    // ---- parsePathFacts ------------------------------------------------------

    @Test
    void aVertexEdgeVertexPathYieldsTheFactInEdgeDirection() throws Exception {
        String path =
                "[{\"id\": 1, \"label\": \"Entity\", \"properties\": {\"name\": \"Priya\"}}::vertex,"
                        + " {\"id\": 9, \"label\": \"RELATION\", \"start_id\": 1, \"end_id\": 2,"
                        + " \"properties\": {\"type\": \"WORKS_AT\"}}::edge,"
                        + " {\"id\": 2, \"label\": \"Entity\", \"properties\": {\"name\": \"Acme\"}}::vertex]::path";

        List<GraphFact> facts = GraphMemoryAgtype.parsePathFacts(path, null);

        assertThat(facts).containsExactly(new GraphFact("Priya", "WORKS_AT", "Acme"));
    }

    @Test
    void missingTypeFallsBackToRelatedToAndMissingNamesToEmpty() throws Exception {
        String path =
                "[{\"id\": 5, \"label\": \"RELATION\", \"start_id\": 1, \"end_id\": 2,"
                        + " \"properties\": {}}::edge]::path";

        List<GraphFact> facts = GraphMemoryAgtype.parsePathFacts(path, null);

        assertThat(facts).containsExactly(new GraphFact("", "RELATED_TO", ""));
    }

    @Test
    void nullBlankAndNonArrayInputsYieldNoFacts() throws Exception {
        assertThat(GraphMemoryAgtype.parsePathFacts(null, null)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts("   ", null)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts("{\"id\": 1}::vertex", null)).isEmpty();
    }

    @Test
    void aClosedEdgeVoidsTheWholePathWhenNotAskingAsOf() throws Exception {
        String path =
                "[{\"id\": 1, \"label\": \"Entity\", \"properties\": {\"name\": \"A\"}}::vertex,"
                        + " {\"id\": 9, \"label\": \"RELATION\", \"start_id\": 1, \"end_id\": 2,"
                        + " \"properties\": {\"type\": \"X\", \"valid_to\": 500}}::edge,"
                        + " {\"id\": 2, \"label\": \"Entity\", \"properties\": {\"name\": \"B\"}}::vertex]::path";

        // No asOf = "not closed" -> a closed edge returns no facts at all.
        assertThat(GraphMemoryAgtype.parsePathFacts(path, null)).isEmpty();
    }

    @Test
    void asOfMillisGatesOnTheEdgesValidWindow() throws Exception {
        String path =
                "[{\"id\": 1, \"label\": \"Entity\", \"properties\": {\"name\": \"A\"}}::vertex,"
                        + " {\"id\": 9, \"label\": \"RELATION\", \"start_id\": 1, \"end_id\": 2,"
                        + " \"properties\": {\"type\": \"X\", \"valid_from\": 100, \"valid_to\": 500}}::edge,"
                        + " {\"id\": 2, \"label\": \"Entity\", \"properties\": {\"name\": \"B\"}}::vertex]::path";

        assertThat(GraphMemoryAgtype.parsePathFacts(path, 50L)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts(path, 250L))
                .containsExactly(new GraphFact("A", "X", "B"));
        assertThat(GraphMemoryAgtype.parsePathFacts(path, 500L)).isEmpty(); // valid_to is exclusive
    }

    @Test
    void anUnboundedEdgeIsValidAtEveryInstant() throws Exception {
        String path =
                "[{\"id\": 1, \"label\": \"Entity\", \"properties\": {\"name\": \"A\"}}::vertex,"
                        + " {\"id\": 9, \"label\": \"RELATION\", \"start_id\": 1, \"end_id\": 2,"
                        + " \"properties\": {\"type\": \"KNOWS\"}}::edge,"
                        + " {\"id\": 2, \"label\": \"Entity\", \"properties\": {\"name\": \"B\"}}::vertex]::path";

        assertThat(GraphMemoryAgtype.parsePathFacts(path, 0L))
                .containsExactly(new GraphFact("A", "KNOWS", "B"));
    }
}
