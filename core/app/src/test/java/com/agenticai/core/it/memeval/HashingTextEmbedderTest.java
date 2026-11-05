package com.agenticai.core.it.memeval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingTextEmbedderTest {

    @Test
    void vectorsAre384DimensionalUnitLengthAndDeterministic() {
        float[] v = HashingTextEmbedder.embed("Gold SLA is 2 hours");
        assertThat(v).hasSize(384);
        double norm = 0;
        for (float f : v) {
            norm += (double) f * f;
        }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(HashingTextEmbedder.embed("Gold SLA is 2 hours")).containsExactly(v);
    }

    @Test
    void sharedContentWordsScoreFarAboveUnrelatedText() {
        double related = HashingTextEmbedder.similarity("What is the Gold SLA?", "Gold SLA is 2 hours");
        double unrelated = HashingTextEmbedder.similarity("What is the Gold SLA?", "Favourite sweet is kheer");
        assertThat(related).isGreaterThan(0.4);
        assertThat(unrelated).isLessThan(0.2);
        assertThat(related).isGreaterThan(unrelated + 0.3);
    }

    @Test
    void pluralsAndTypoStemsStayClose() {
        assertThat(HashingTextEmbedder.similarity("invoices", "invoice")).isGreaterThan(0.8);
        assertThat(HashingTextEmbedder.similarity("approved refunds", "refund approved")).isGreaterThan(0.6);
    }

    @Test
    void synonymsAreInvisibleToItByDesign() {
        assertThat(HashingTextEmbedder.similarity("my car", "I drive a vehicle")).isLessThan(0.2);
    }

    @Test
    void hindiWordsAreOrdinaryTokens() {
        assertThat(HashingTextEmbedder.similarity("मेरा पालतू कुत्ता", "पालतू कुत्ता टॉमी है")).isGreaterThan(0.4);
    }

    @Test
    void textWithoutContentWordsMapsToOneFixedVector() {
        assertThat(HashingTextEmbedder.embed("the of and")).containsExactly(HashingTextEmbedder.embed(""));
        assertThat(HashingTextEmbedder.contentWords(null)).isEmpty();
    }
}
