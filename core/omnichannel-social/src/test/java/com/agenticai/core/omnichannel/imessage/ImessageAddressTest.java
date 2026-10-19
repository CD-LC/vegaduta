package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImessageAddressTest {

    @Test
    void phoneNumbersCollapseToOneForm() {
        assertThat(ImessageAddress.normalize("+1 (555) 010-0100")).isEqualTo("+15550100100");
        assertThat(ImessageAddress.normalize("+15550100100")).isEqualTo("+15550100100");
        assertThat(ImessageAddress.normalize("555.010.0100")).isEqualTo("+5550100100");
    }

    @Test
    void emailsAreLowerCased() {
        assertThat(ImessageAddress.normalize(" Foo@Example.COM ")).isEqualTo("foo@example.com");
    }

    @Test
    void junkIsRejected() {
        assertThat(ImessageAddress.normalize(null)).isNull();
        assertThat(ImessageAddress.normalize("  ")).isNull();
        assertThat(ImessageAddress.normalize("12345")).isNull();
        assertThat(ImessageAddress.normalize("call-me-maybe")).isNull();
        assertThat(ImessageAddress.normalize("a@@b.com")).isNull();
        assertThat(ImessageAddress.normalize("@example.com")).isNull();
        assertThat(ImessageAddress.normalize("1234567890123456789")).isNull();
    }

    @Test
    void listsParseAcrossSeparatorsAndDropUnusableEntries() {
        assertThat(ImessageAddress.parseList("+1 555 010 0100, Bob@x.com;\nnonsense\n+15550100100"))
                .containsExactly("+15550100100", "bob@x.com");
        assertThat(ImessageAddress.parseList(null)).isEmpty();
    }

    @Test
    void listsAreCapped() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < ImessageAddress.MAX_ALLOWED_SENDERS + 50; i++) {
            many.append("+1555").append(String.format("%07d", i)).append(',');
        }
        assertThat(ImessageAddress.parseList(many.toString())).hasSize(ImessageAddress.MAX_ALLOWED_SENDERS);
    }

    @Test
    void maskKeepsOnlyTheLastFour() {
        assertThat(ImessageAddress.mask("+15550100100")).isEqualTo("***0100");
        assertThat(ImessageAddress.mask("ab")).isEqualTo("***");
    }
}
