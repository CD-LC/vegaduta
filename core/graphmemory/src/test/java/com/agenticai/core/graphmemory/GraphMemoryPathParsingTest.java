package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link GraphMemoryAgtype#parsePathFacts} against agtype text of the shape captured verbatim from
 * a real Apache AGE 1.6.0 / PostgreSQL 16 ({@code MATCH p = ... RETURN p}, 2026-10-05).
 */
class GraphMemoryPathParsingTest {

    private static final String ONE_HOP =
        "[{\"id\": 1125899906842625, \"label\": \"Entity\", \"properties\": {\"name\": \"Priya\", \"agent_id\": \"x\", \"subject_key\": \"s\"}}::vertex, "
            + "{\"id\": 1407374883553281, \"label\": \"RELATION\", \"end_id\": 1125899906842626, \"start_id\": 1125899906842625, "
            + "\"properties\": {\"type\": \"MANAGER\", \"valid_from\": 1000}}::edge, "
            + "{\"id\": 1125899906842626, \"label\": \"Entity\", \"properties\": {\"name\": \"Raj\", \"agent_id\": \"x\", \"subject_key\": \"s\"}}::vertex]::path";

    /** Same edge, walked from the other end: the path starts at Raj but the edge still runs Priya -> Raj. */
    private static final String ONE_HOP_REVERSED =
        "[{\"id\": 1125899906842626, \"label\": \"Entity\", \"properties\": {\"name\": \"Raj\"}}::vertex, "
            + "{\"id\": 1407374883553281, \"label\": \"RELATION\", \"end_id\": 1125899906842626, \"start_id\": 1125899906842625, "
            + "\"properties\": {\"type\": \"MANAGER\", \"valid_from\": 1000}}::edge, "
            + "{\"id\": 1125899906842625, \"label\": \"Entity\", \"properties\": {\"name\": \"Priya\"}}::vertex]::path";

    private static final String CLOSED_EDGE =
        "[{\"id\": 1, \"label\": \"Entity\", \"properties\": {\"name\": \"User\"}}::vertex, "
            + "{\"id\": 3, \"label\": \"RELATION\", \"end_id\": 2, \"start_id\": 1, "
            + "\"properties\": {\"type\": \"LIVES_IN\", \"valid_from\": 1000, \"valid_to\": 2000}}::edge, "
            + "{\"id\": 2, \"label\": \"Entity\", \"properties\": {\"name\": \"Pune\"}}::vertex]::path";

    @Test
    void factsKeepTheDirectionTheEdgeWasWrittenIn() throws Exception {
        assertThat(GraphMemoryAgtype.parsePathFacts(ONE_HOP, null)).containsExactly(new GraphFact("Priya", "MANAGER", "Raj"));
        assertThat(GraphMemoryAgtype.parsePathFacts(ONE_HOP_REVERSED, null)).containsExactly(new GraphFact("Priya", "MANAGER", "Raj"));
    }

    @Test
    void aClosedEdgeIsHiddenNowButVisibleInsideItsWindow() throws Exception {
        assertThat(GraphMemoryAgtype.parsePathFacts(CLOSED_EDGE, null)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts(CLOSED_EDGE, 999L)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts(CLOSED_EDGE, 1000L)).containsExactly(new GraphFact("User", "LIVES_IN", "Pune"));
        assertThat(GraphMemoryAgtype.parsePathFacts(CLOSED_EDGE, 1999L)).hasSize(1);
        assertThat(GraphMemoryAgtype.parsePathFacts(CLOSED_EDGE, 2000L)).as("valid_to is exclusive").isEmpty();
    }

    @Test
    void anEdgeWithoutTemporalPropertiesIsAlwaysValid() throws Exception {
        String legacy = ONE_HOP.replace(", \"valid_from\": 1000", "");
        assertThat(GraphMemoryAgtype.parsePathFacts(legacy, null)).hasSize(1);
        assertThat(GraphMemoryAgtype.parsePathFacts(legacy, 5L)).hasSize(1);
    }

    @Test
    void aPathIsDroppedWhenAnyEdgeOnItIsInvalid() throws Exception {
        String twoHop = "[{\"id\": 1, \"properties\": {\"name\": \"A\"}}::vertex, "
            + "{\"id\": 10, \"end_id\": 2, \"start_id\": 1, \"properties\": {\"type\": \"R1\", \"valid_from\": 1}}::edge, "
            + "{\"id\": 2, \"properties\": {\"name\": \"B\"}}::vertex, "
            + "{\"id\": 11, \"end_id\": 3, \"start_id\": 2, \"properties\": {\"type\": \"R2\", \"valid_from\": 1, \"valid_to\": 5}}::edge, "
            + "{\"id\": 3, \"properties\": {\"name\": \"C\"}}::vertex]::path";
        assertThat(GraphMemoryAgtype.parsePathFacts(twoHop, null)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts(twoHop, 4L))
            .containsExactly(new GraphFact("A", "R1", "B"), new GraphFact("B", "R2", "C"));
    }

    @Test
    void namesWithQuotesAndUnicodeSurviveTheJsonRoundTrip() throws Exception {
        String path = "[{\"id\": 1, \"properties\": {\"name\": \"O'Brien\"}}::vertex, "
            + "{\"id\": 3, \"end_id\": 2, \"start_id\": 1, \"properties\": {\"type\": \"SAID\", \"valid_from\": 1}}::edge, "
            + "{\"id\": 2, \"properties\": {\"name\": \"He said \\\"hi\\\" \\u0905\"}}::vertex]::path";
        List<GraphFact> facts = GraphMemoryAgtype.parsePathFacts(path, null);
        assertThat(facts).containsExactly(new GraphFact("O'Brien", "SAID", "He said \"hi\" \u0905"));
    }

    @Test
    void blankOrNonPathInputYieldsNothing() throws Exception {
        assertThat(GraphMemoryAgtype.parsePathFacts(null, null)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts("  ", null)).isEmpty();
        assertThat(GraphMemoryAgtype.parsePathFacts("{\"id\": 1}", null)).isEmpty();
    }
}
