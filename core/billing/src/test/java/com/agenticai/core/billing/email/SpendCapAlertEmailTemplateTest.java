package com.agenticai.core.billing.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SpendCapAlertEmailTemplateTest {

    private final BillingEmailTemplateService templates = new BillingEmailTemplateService();

    @Test
    void eightyPercentMailNamesSpendCapAndReset() {
        String html =
                templates.renderSpendCapAlert(
                        "Acme",
                        80,
                        new BigDecimal("80.1234"),
                        new BigDecimal("100"),
                        "1 November 2026");
        assertTrue(html.contains("80% of your monthly AI spend limit used"));
        assertTrue(html.contains("$80.12"));
        assertTrue(html.contains("$100.00"));
        assertTrue(html.contains("1 November 2026"));
        assertTrue(html.contains("BYOK"));
    }

    @Test
    void hundredPercentMailSaysPaused() {
        String html =
                templates.renderSpendCapAlert(
                        "Acme", 100, new BigDecimal("100"), new BigDecimal("100"), "d");
        assertTrue(html.contains("limit reached"));
        assertTrue(html.contains("paused"));
    }

    @Test
    void tenantNameAndDateAreHtmlEscaped() {
        String html =
                templates.renderSpendCapAlert(
                        "<script>x</script>", 100, BigDecimal.ONE, BigDecimal.TEN, "<img src=x>");
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<img"));
    }
}
