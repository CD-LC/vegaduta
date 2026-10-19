package com.agenticai.core.billing.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompGrantEmailTemplatesTest {

    private final BillingEmailTemplateService templates = new BillingEmailTemplateService();

    @Test
    void reminderNamesTheDateTheTimeLeftAndTheUpgradeLink() {
        String html = templates.renderCompGrantReminder("Acme", "11 Dec 2026", 10, "https://app.example/billing");
        assertTrue(html.contains("ends in 10 days"));
        assertTrue(html.contains("11 Dec 2026"));
        assertTrue(html.contains("href=\"https://app.example/billing\""));
        assertTrue(html.contains("Free plan"));
    }

    @Test
    void reminderSaysTomorrowForTheLastDay() {
        assertTrue(templates.renderCompGrantReminder("Acme", "2 Dec 2026", 1, "https://x").contains("ends tomorrow"));
    }

    @Test
    void tenantNameIsHtmlEscapedInBothMails() {
        String evil = "<script>alert(1)</script>";
        assertFalse(templates.renderCompGrantReminder(evil, "d", 3, "https://x").contains("<script>"));
        assertFalse(templates.renderCompGrantEnded(evil, "https://x").contains("<script>"));
    }

    @Test
    void endedMailLinksToThePlans() {
        String html = templates.renderCompGrantEnded("Acme", "https://app.example/billing");
        assertTrue(html.contains("now on the Free plan"));
        assertTrue(html.contains("https://app.example/billing"));
    }
}
