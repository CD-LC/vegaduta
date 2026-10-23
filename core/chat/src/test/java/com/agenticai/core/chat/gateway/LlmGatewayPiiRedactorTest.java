package com.agenticai.core.chat.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LlmGatewayPiiRedactorTest {

    @Test
    void returnsTextUnchangedWhenNoCategoriesEnabled() {
        String text = "contact me at a@b.com";
        assertSame(text, LlmGatewayPiiRedactor.redact(text, Set.of()));
    }

    @Test
    void returnsBlankOrNullTextUnchanged() {
        assertEquals(
                null,
                LlmGatewayPiiRedactor.redact(null, EnumSet.allOf(LlmGatewayPiiCategory.class)));
        assertEquals(
                "   ",
                LlmGatewayPiiRedactor.redact("   ", EnumSet.allOf(LlmGatewayPiiCategory.class)));
    }

    @Test
    void redactsEmailWhenCategoryEnabled() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "reach me at jane.doe@example.com please",
                        Set.of(LlmGatewayPiiCategory.EMAIL));
        assertEquals("reach me at [REDACTED_EMAIL] please", result);
    }

    @Test
    void leavesEmailAloneWhenCategoryDisabled() {
        String text = "reach me at jane.doe@example.com please";
        String result = LlmGatewayPiiRedactor.redact(text, Set.of(LlmGatewayPiiCategory.PHONE));
        assertEquals(text, result);
    }

    @Test
    void redactsIndianPhoneNumberWhenCategoryEnabled() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "call 98765 43210 tomorrow", Set.of(LlmGatewayPiiCategory.PHONE));
        assertEquals("call [REDACTED_PHONE] tomorrow", result);
    }

    @Test
    void redactsNanpPhoneNumberWhenCategoryEnabled() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "call (555) 123-4567 tomorrow", Set.of(LlmGatewayPiiCategory.PHONE));
        assertEquals("call [REDACTED_PHONE] tomorrow", result);
    }

    @Test
    void redactsSsnAsGovernmentId() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "my ssn is 123-45-6789 ok", Set.of(LlmGatewayPiiCategory.GOVERNMENT_ID));
        assertEquals("my ssn is [REDACTED_GOVERNMENT_ID] ok", result);
    }

    @Test
    void redactsAadhaarAsGovernmentId() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "aadhaar 1234 5678 9012 here", Set.of(LlmGatewayPiiCategory.GOVERNMENT_ID));
        assertEquals("aadhaar [REDACTED_GOVERNMENT_ID] here", result);
    }

    @Test
    void redactsPanAsGovernmentId() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "pan ABCDE1234F is mine", Set.of(LlmGatewayPiiCategory.GOVERNMENT_ID));
        assertEquals("pan [REDACTED_GOVERNMENT_ID] is mine", result);
    }

    @Test
    void redactsCreditCardNumber() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "card 4111 1111 1111 1111 expires soon",
                        Set.of(LlmGatewayPiiCategory.CREDIT_CARD));
        assertEquals("card [REDACTED_CARD] expires soon", result);
    }

    @Test
    void redactsOnlyEnabledCategoriesTogether() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "email a@b.com card 4111 1111 1111 1111",
                        EnumSet.of(LlmGatewayPiiCategory.EMAIL));
        assertTrue(result.contains("[REDACTED_EMAIL]"));
        assertTrue(result.contains("4111 1111 1111 1111"));
    }

    @Test
    void redactsAllEnabledCategoriesInOnePass() {
        String result =
                LlmGatewayPiiRedactor.redact(
                        "email a@b.com card 4111 1111 1111 1111",
                        EnumSet.allOf(LlmGatewayPiiCategory.class));
        assertTrue(result.contains("[REDACTED_EMAIL]"));
        assertTrue(result.contains("[REDACTED_CARD]"));
    }

    @Test
    void longTextFullOfEmailCharactersButNoAtSignIsRedactedInLinearTime() {
        for (String unit : new String[] {"b.", "b-", "b"}) {
            String hostile = unit.repeat(25_000);
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () ->
                            LlmGatewayPiiRedactor.redact(
                                    hostile, Set.of(LlmGatewayPiiCategory.EMAIL)));
        }
        assertEquals(
                "[REDACTED_EMAIL]",
                LlmGatewayPiiRedactor.redact(
                        "first.last+tag@sub.example.co.in", Set.of(LlmGatewayPiiCategory.EMAIL)));
    }

    @Test
    void anAddressIsRedactedWholeWhateverTheLengthOfItsLocalPart() {
        for (int length : new int[] {63, 64, 65, 70, 300, 5_000}) {
            String text = "mail " + "a".repeat(length) + "@example.com please";
            String result = LlmGatewayPiiRedactor.redact(text, Set.of(LlmGatewayPiiCategory.EMAIL));
            assertEquals("mail [REDACTED_EMAIL] please", result, "local part of " + length);
        }
    }

    @Test
    void anAdversarialTextOf50kCharactersIsRedactedQuickly() {
        for (String hostile :
                new String[] {
                    "a".repeat(50_000),
                    "a@".repeat(25_000),
                    "a".repeat(63) + "@" + "b.".repeat(25_000),
                    "a@b." + "c".repeat(50_000) + "_"
                }) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () ->
                            LlmGatewayPiiRedactor.redact(
                                    hostile, Set.of(LlmGatewayPiiCategory.EMAIL)));
        }
    }
}
