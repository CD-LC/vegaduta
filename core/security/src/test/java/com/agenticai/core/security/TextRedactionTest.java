package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TextRedactionTest {

    private static final String BODY = "MIIEvQIBADANBgkqhkiG9w0BAQEFAASC\nKcwggSjAgEAAoIBAQC7\n";
    private static final Duration BUDGET = Duration.ofSeconds(2);

    // ---- e-mail ----------------------------------------------------------------------------

    @Test
    void ordinaryAddressesAreRedactedAndTheTextAroundThemIsUntouched() {
        assertThat(TextRedaction.redactEmails("write to jane.doe@example.com now", "[E]"))
                .isEqualTo("write to [E] now");
        assertThat(TextRedaction.redactEmails("first.last+tag@sub.example.co.in", "[E]"))
                .isEqualTo("[E]");
        assertThat(TextRedaction.redactEmails("a@b.com, c@d.org;e@f.io", "[E]"))
                .isEqualTo("[E], [E];[E]");
        assertThat(TextRedaction.redactEmails("no address here, 5 @ home", "[E]"))
                .isEqualTo("no address here, 5 @ home");
        assertThat(TextRedaction.redactEmails("", "[E]")).isEmpty();
        assertThat(TextRedaction.redactEmails(null, "[E]")).isNull();
        assertThat(TextRedaction.containsEmail("jane@example.com")).isTrue();
        assertThat(TextRedaction.containsEmail("jane at example dot com")).isFalse();
        assertThat(TextRedaction.containsEmail(null)).isFalse();
    }

    @Test
    void theReplacementIsLiteralNotARegexReplacement() {
        assertThat(TextRedaction.redactEmails("a@b.com", "$1\\")).isEqualTo("$1\\");
    }

    @Test
    void anAddressWithAnOverLongLocalPartIsStillRedactedWhole() {
        for (int length : new int[] {63, 64, 65, 70, 300, 5_000}) {
            String local = "a".repeat(length);
            String text = "mail " + local + "@example.com please";
            assertThat(TextRedaction.redactEmails(text, "[E]"))
                    .as("local part of %d characters", length)
                    .isEqualTo("mail [E] please");
            assertThat(TextRedaction.containsEmail(local + "@example.com")).isTrue();
        }
        // Mixed address characters, longer than the 64-character window.
        String mixed = "first.last+tag_%-".repeat(10) + "x";
        assertThat(TextRedaction.redactEmails(mixed + "@example.org", "[E]")).isEqualTo("[E]");
    }

    @Test
    void aLongLocalPartRightAfterAnotherAddressIsNotConfusedWithIt() {
        String text = "a@b.com " + "z".repeat(100) + "@c.org";
        assertThat(TextRedaction.redactEmails(text, "[E]")).isEqualTo("[E] [E]");
    }

    @Test
    void longRunsOfAddressCharactersAreScannedInLinearTime() {
        for (String unit : new String[] {"a", "a.", "a-", "%", "a%b+"}) {
            String hostile = unit.repeat(50_000);
            String out =
                    assertTimeoutPreemptively(
                            BUDGET, () -> TextRedaction.redactEmails(hostile, "[E]"));
            assertThat(out).isEqualTo(hostile);
            assertTimeoutPreemptively(BUDGET, () -> TextRedaction.containsEmail(hostile));
        }
    }

    @Test
    void manyAtSignsAndLongDomainsAreScannedInLinearTime() {
        for (String hostile :
                new String[] {
                    "a@".repeat(50_000),
                    "@".repeat(50_000),
                    "a@b".repeat(30_000),
                    "a".repeat(63) + "@" + "b.".repeat(25_000),
                    "a@" + "b".repeat(50_000),
                    ("x".repeat(63) + "@").repeat(800),
                    "a@b." + "c".repeat(50_000) + "_"
                }) {
            assertTimeoutPreemptively(BUDGET, () -> TextRedaction.redactEmails(hostile, "[E]"));
        }
    }

    @Test
    void aHugeTextOfRealAddressesIsRedactedInLinearTime() {
        String text = "user.name@example.com, ".repeat(10_000);
        String out = assertTimeoutPreemptively(BUDGET, () -> TextRedaction.redactEmails(text, "x"));
        assertThat(out).isEqualTo("x, ".repeat(10_000));
    }

    @Test
    void spansNeverOverlapAndAreInOrder() {
        String text = "a@b.com," + "c".repeat(80) + "@d.org";
        var spans = TextRedaction.emailSpans(text);
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0)[1]).isLessThanOrEqualTo(spans.get(1)[0]);
    }

    // ---- PEM private keys --------------------------------------------------------------------

    @Test
    void aWholePrivateKeyBlockIsRedacted() {
        String key = "-----BEGIN PRIVATE KEY-----\n" + BODY + "-----END PRIVATE KEY-----";
        assertThat(TextRedaction.redactPrivateKeyBlocks("before " + key + " after", "[K]"))
                .isEqualTo("before [K] after");
        String rsa = "-----BEGIN RSA PRIVATE KEY-----\n" + BODY + "-----END RSA PRIVATE KEY-----";
        String ec = "-----BEGIN EC PRIVATE KEY-----\n" + BODY + "-----END EC PRIVATE KEY-----";
        assertThat(TextRedaction.redactPrivateKeyBlocks(key + " and " + rsa + "\n" + ec, "[K]"))
                .isEqualTo("[K] and [K]\n[K]");
        assertThat(
                        TextRedaction.redactPrivateKeyBlocks(
                                "-----BEGIN ENCRYPTED PRIVATE KEY-----\n"
                                        + BODY
                                        + "-----END ENCRYPTED PRIVATE KEY-----",
                                "[K]"))
                .isEqualTo("[K]");
    }

    @Test
    void textWithoutACompleteKeyBlockIsReturnedUnchanged() {
        for (String text :
                new String[] {
                    "",
                    "nothing secret",
                    "-----BEGIN PRIVATE KEY-----\n" + BODY, // no END line
                    "-----BEGIN CERTIFICATE-----\n" + BODY + "-----END CERTIFICATE-----",
                    "-----BEGIN PUBLIC KEY-----\n" + BODY + "-----END PUBLIC KEY-----",
                    "-----BEGIN PRIVATE KEY-----\n" + BODY + "-----END PUBLIC KEY-----",
                    "-----BEGIN private key-----\n" + BODY + "-----END private key-----",
                    "-----BEGIN PRIVATE KEY----\n" + BODY + "-----END PRIVATE KEY-----"
                }) {
            assertThat(TextRedaction.redactPrivateKeyBlocks(text, "[K]")).isEqualTo(text);
        }
        assertThat(TextRedaction.redactPrivateKeyBlocks(null, "[K]")).isNull();
    }

    @Test
    void aBlockRunsFromItsFirstBeginToTheFirstMatchingEnd() {
        // Same as the lazy pattern this replaces: a stray header before the real one is swallowed,
        // so nothing of the key can survive in front of it.
        String text =
                "-----BEGIN PRIVATE KEY-----\nnote\n-----BEGIN PRIVATE KEY-----\n"
                        + BODY
                        + "-----END PRIVATE KEY-----tail";
        assertThat(TextRedaction.redactPrivateKeyBlocks(text, "[K]")).isEqualTo("[K]tail");
        // An END line for something else does not close the block.
        String mixed =
                "-----BEGIN PRIVATE KEY-----\n"
                        + BODY
                        + "-----END CERTIFICATE-----\nmore\n-----END PRIVATE KEY-----!";
        assertThat(TextRedaction.redactPrivateKeyBlocks(mixed, "[K]")).isEqualTo("[K]!");
    }

    @Test
    void adversarialKeyTextIsScannedInLinearTime() {
        for (String hostile :
                new String[] {
                    "-----BEGIN PRIVATE KEY-----".repeat(100_000),
                    "-----BEGIN PRIVATE KEY-----\n".repeat(50_000) + "-----END CERTIFICATE-----",
                    "-----END PRIVATE KEY-----".repeat(100_000),
                    "-----BEGIN ".repeat(100_000),
                    "-----BEGIN " + " ".repeat(200_000),
                    "-----BEGIN " + "A".repeat(200_000),
                    ("-----BEGIN " + "A".repeat(100)).repeat(5_000),
                    "-----BEGIN PRIVATE KEY-----" + "-----END ".repeat(100_000),
                    "-----BEGIN PRIVATE KEY-----" + ("-----END " + "A".repeat(50)).repeat(5_000)
                }) {
            String out =
                    assertTimeoutPreemptively(
                            BUDGET, () -> TextRedaction.redactPrivateKeyBlocks(hostile, "[K]"));
            assertThat(out).isEqualTo(hostile);
        }
    }

    @Test
    void manyCompleteKeyBlocksAreRedactedInLinearTime() {
        String block = "-----BEGIN PRIVATE KEY-----\n" + BODY + "-----END PRIVATE KEY-----\n";
        String out =
                assertTimeoutPreemptively(
                        BUDGET,
                        () -> TextRedaction.redactPrivateKeyBlocks(block.repeat(20_000), "[K]"));
        assertThat(out).isEqualTo("[K]\n".repeat(20_000));
    }
}
