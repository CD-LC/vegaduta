package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link GraphMemoryCypher}: the {@code ag_catalog.cypher(...)} wrapper with its graph-name
 * allowlist, the two invalidation statement builders (forget by exact names, supersede by
 * case-folded endpoints) and the single-edge temporal filter.
 */
class GraphMemoryCypherTest {

    // ---- cypherCall ---------------------------------------------------------

    @Test
    void cypherCallWrapsTheQueryInTheAgCatalogShell() {
        String sql = GraphMemoryCypher.cypherCall("acme_kg", "MATCH (n) RETURN n", "n agtype");

        assertThat(sql)
                .isEqualTo(
                        "SELECT * FROM ag_catalog.cypher('acme_kg', $$ MATCH (n) RETURN n $$) AS (n agtype)");
    }

    @Test
    void graphNamesOutsideTheAllowlistAreRejected() {
        for (String bad :
                new String[] {
                    null, "", "Tenant_KG", "kg-name", "name;x", "9lives_kg", "a b", "name.kg"
                }) {
            assertThatThrownBy(() -> GraphMemoryCypher.cypherCall(bad, "c", "cols"))
                    .as("graph name %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not a plain AGE graph name");
        }
    }

    @Test
    void lowercaseSnakeCaseGraphNamesAreAccepted() {
        assertThat(GraphMemoryCypher.cypherCall("t_9lives_kg", "c", "cols"))
                .contains("t_9lives_kg");
        assertThat(GraphMemoryCypher.cypherCall("_kg", "c", "cols")).contains("'_kg'");
    }

    // ---- invalidation builders ------------------------------------------------

    @Test
    void forgetInvalidatesTheExactlyNamedOpenEdge() {
        String cypher =
                GraphMemoryCypher.buildForgetInvalidateCypher(
                        "agent_1",
                        "subj_2",
                        new GraphFactTextMatcher.ValidEdge("Priya", "WORKS_AT", "Acme"),
                        1234L);

        assertThat(cypher)
                .isEqualTo(
                        "MATCH (a:Entity {name: 'Priya', agent_id: 'agent_1', subject_key: 'subj_2'})"
                                + "-[r:RELATION {type: 'WORKS_AT'}]->"
                                + "(b:Entity {name: 'Acme', agent_id: 'agent_1', subject_key: 'subj_2'}) "
                                + "WHERE r.valid_to IS NULL SET r.valid_to = 1234");
    }

    @Test
    void supersedeClosesOpenEdgesToOtherObjectsCaseFolded() {
        String cypher =
                GraphMemoryCypher.buildSupersedeInvalidateCypher(
                        "agent_1", "subj_2", new GraphTriple("Priya", "works_at", "Acme"), 999L);

        assertThat(cypher)
                .isEqualTo(
                        "MATCH (a:Entity {agent_id: 'agent_1', subject_key: 'subj_2'})"
                                + "-[r:RELATION {type: 'WORKS_AT'}]->"
                                + "(b:Entity {agent_id: 'agent_1', subject_key: 'subj_2'}) "
                                + "WHERE toLower(a.name) = 'priya' AND toLower(b.name) <> 'acme' "
                                + "AND r.valid_to IS NULL SET r.valid_to = 999");
    }

    @Test
    void interpolatedValuesAreSanitizedThroughTheEscaper() {
        String cypher =
                GraphMemoryCypher.buildForgetInvalidateCypher(
                        "a", "s", new GraphFactTextMatcher.ValidEdge("O'Brien", "KNOWS", "x"), 1L);

        // The escaper's \' form - a bare quote must never reach the cypher text.
        assertThat(cypher).contains("O\\'Brien");
        assertThat(cypher.replace("\\'", "")).doesNotContain("O'Brien");
    }

    // ---- singleEdgeFilter ------------------------------------------------------

    @Test
    void noAsOfMeansOnlyOpenEdges() {
        assertThat(GraphMemoryCypher.singleEdgeFilter(null)).isEqualTo("r.valid_to IS NULL");
    }

    @Test
    void asOfAddsBothWindowBounds() {
        assertThat(GraphMemoryCypher.singleEdgeFilter(250L))
                .isEqualTo(
                        "(r.valid_from IS NULL OR r.valid_from <= 250) "
                                + "AND (r.valid_to IS NULL OR r.valid_to > 250)");
    }
}
