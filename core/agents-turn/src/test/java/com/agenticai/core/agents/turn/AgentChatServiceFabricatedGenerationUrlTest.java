package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link AgentChatService#containsFabricatedGenerationUrl} - live incident, 2026-08-10: despite
 * {@code TOOL_TRUST_DIRECTIVE} already forbidding it in the system prompt, a hosted model twice
 * emitted a plausible-looking but entirely fabricated {@code
 * oaidalleapiprodscus.blob.core.windows.net} URL with an invented SAS signature - the target
 * 404s/auth-fails, since a real one can only ever come from an actual generation-tool result.
 * This is the deterministic backstop for when prompt instructions alone don't hold; see {@link
 * AgentChatService#recordAndCorrectFabricatedGenerationUrl} for what happens once this fires.
 */
class AgentChatServiceFabricatedGenerationUrlTest {

    @Test
    void detectsTheConfirmedLiveIncidentDomain() {
        String answer = "Here is your file: https://oaidalleapiprodscus.blob.core.windows.net/private/"
            + "org-p5212OqI5s3s2q5s5s6s7s8s/user-p5212OqI5s3s2q5s5s6s7s8s/generations/generation-vB0/"
            + "file.pdf?st=2024-05-23T05%3A24%3A45Z&se=2024-05-23T07%3A24%3A45Z&sp=r&sv=2023-11-03&sr=b&sig=abc123";

        assertTrue(ChatPromptDirectives.containsFabricatedGenerationUrl(answer));
    }

    @Test
    void isCaseInsensitive() {
        String answer = "Download it here: https://OAIDALLEAPIPRODSCUS.BLOB.CORE.WINDOWS.NET/private/x/file.pdf";

        assertTrue(ChatPromptDirectives.containsFabricatedGenerationUrl(answer));
    }

    @Test
    void ordinaryAnswersWithNoGenerationClaimDoNotTrigger() {
        assertFalse(ChatPromptDirectives.containsFabricatedGenerationUrl(
            "Koramangala Police Station is located near the main circle."));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertFalse(ChatPromptDirectives.containsFabricatedGenerationUrl(null));
        assertFalse(ChatPromptDirectives.containsFabricatedGenerationUrl(""));
        assertFalse(ChatPromptDirectives.containsFabricatedGenerationUrl("   "));
    }

    @Test
    void aRealUnrelatedUrlDoesNotFalsePositive() {
        assertFalse(ChatPromptDirectives.containsFabricatedGenerationUrl(
            "You can read more at https://mysore.ksp.gov.in/contact-us"));
    }

    /** Stage, 2026-10-15, llama3.2:3b: no tool call, a narrated run and an invented host. */
    @Test
    void detectsANarratedRunWithAnInventedFileUrl() {
        String answer = "The generate_document tool is running.\n\n"
            + "Download Link:\n\nThe report will be available for download at: "
            + "<https://example.com/mandi-prices-weather-report-2026-10-15.pdf>\n\n"
            + "Please note that you will receive an email with the download link once the report is ready.";
        assertTrue(ChatPromptDirectives.containsFabricatedGenerationUrl(answer));
    }

    @Test
    void aFileUrlNeedsAGenerationClaimNextToIt() {
        assertFalse(ChatPromptDirectives.containsFabricatedGenerationUrl(
            "The statute is published at https://egazette.gov.in/notification-2024.pdf, see section 4."));
        assertTrue(ChatPromptDirectives.containsFabricatedGenerationUrl(
            "Here is your report: https://files.invented.io/out/report.pdf"));
        assertTrue(ChatPromptDirectives.containsFabricatedGenerationUrl(
            "Your chart has been created: https://cdn.invented.io/chart.png."));
    }

    @Test
    void aPageUrlWithAGenerationWordIsNotAFileClaim() {
        assertFalse(ChatPromptDirectives.containsFabricatedGenerationUrl(
            "You can download the app from https://play.google.com/store/apps/details?id=x"));
    }
}
