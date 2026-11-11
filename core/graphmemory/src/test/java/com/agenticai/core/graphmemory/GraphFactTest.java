package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Covers the two tiny value types facts flow through the module as: {@link GraphFact} (the recalled
 * relationship, with its plain-English rendering for LLM context) and {@link GraphTriple} (the raw
 * extraction result).
 */
class GraphFactTest {

    @Test
    void asSentenceLowercasesTheRelationAndUnsnakesIt() {
        assertThat(new GraphFact("Priya", "MANAGER_OF_BY", "Raj").asSentence())
                .isEqualTo("Priya manager of by Raj");
        assertThat(new GraphFact("User", "LIKES", "Tea").asSentence()).isEqualTo("User likes Tea");
    }

    @Test
    void recordAccessorsAndEquality() {
        GraphFact f = new GraphFact("A", "KNOWS", "B");

        assertThat(f.subject()).isEqualTo("A");
        assertThat(f.relation()).isEqualTo("KNOWS");
        assertThat(f.object()).isEqualTo("B");
        assertThat(f).isEqualTo(new GraphFact("A", "KNOWS", "B"));
        assertThat(f).isNotEqualTo(new GraphFact("A", "KNOWS", "C"));
    }

    @Test
    void graphTripleIsAPlainCarrier() {
        GraphTriple t = new GraphTriple("Priya", "WORKS_AT", "Acme");

        assertThat(t.subject()).isEqualTo("Priya");
        assertThat(t.relation()).isEqualTo("WORKS_AT");
        assertThat(t.object()).isEqualTo("Acme");
        assertThat(t).isEqualTo(new GraphTriple("Priya", "WORKS_AT", "Acme"));
        assertThat(t.hashCode()).isEqualTo(new GraphTriple("Priya", "WORKS_AT", "Acme").hashCode());
    }
}
