package com.agenticai.core.agents.importer;

import static com.agenticai.core.agents.importer.AgentImportFixtures.json;
import static com.agenticai.core.agents.importer.AgentImportFixtures.parse;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.Test;

class AgentImportHelpersTest {

    // ---- text
    // ------------------------------------------------------------------------------------------

    @Test
    void sanitizeNameStripsControlAndAngleBracketsAndCaps() {
        assertThat(AgentImportText.sanitizeName("  My\u0000 <b>Agent</b>\n\tv2 ", "x"))
                .isEqualTo("My b Agent /b v2");
        assertThat(AgentImportText.sanitizeName("\u0001\u0002", "fallback")).isEqualTo("fallback");
        assertThat(AgentImportText.sanitizeName(null, "fallback")).isEqualTo("fallback");
        assertThat(AgentImportText.sanitizeName("a".repeat(500), "x"))
                .hasSize(AgentImportText.NAME_MAX);
    }

    @Test
    void truncateNeverSplitsASurrogatePair() {
        String s = "ab😀cd"; // ab + emoji + cd
        assertThat(AgentImportText.truncate(s, 3)).isEqualTo("ab");
        assertThat(AgentImportText.truncate(s, 4)).isEqualTo("ab😀");
        assertThat(AgentImportText.truncate(null, 3)).isEmpty();
    }

    @Test
    void htmlToTextOnlyTouchesRealHtml() {
        assertThat(AgentImportText.htmlToText("<p>Hi &amp; <em>bye</em></p><p>Next</p>"))
                .isEqualTo("Hi & bye\nNext");
        // plain prompts with XML-ish tags must survive untouched
        String plain = "Use <context>...</context> to answer.";
        assertThat(AgentImportText.htmlToText(plain)).isEqualTo(plain);
        assertThat(AgentImportText.htmlToText(null)).isEmpty();
    }

    @Test
    void htmlToTextSurvivesAPathologicalTagRun() {
        String hostile = "<p>" + "<".repeat(50_000) + "</p>";
        long start = System.nanoTime();
        String out = AgentImportText.htmlToText(hostile);
        assertThat(out).isNotNull();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void forLogIsOneShortLine() {
        String s = AgentImportText.forLog("line1\nline2\u0007" + "x".repeat(1000));
        assertThat(s).doesNotContain("\n").hasSizeLessThanOrEqualTo(120);
    }

    // ---- json / credentials
    // ------------------------------------------------------------------------------

    @Test
    void numberAcceptsNumericStringsButNothingElse() {
        assertThat(AgentImportJson.number(parse("{\"t\":\"0.5\"}"), "t")).isEqualTo(0.5);
        assertThat(AgentImportJson.number(parse("{\"t\":0.25}"), "t")).isEqualTo(0.25);
        assertThat(AgentImportJson.number(parse("{\"t\":\"\"}"), "t")).isNull();
        assertThat(AgentImportJson.number(parse("{\"t\":\"NaN\"}"), "t")).isNull();
        assertThat(AgentImportJson.number(parse("{\"t\":[1]}"), "t")).isNull();
        assertThat(AgentImportJson.number(parse("[1]"), "t")).isNull();
    }

    @Test
    void secretKeyDetectionDoesNotFlagTokenLimitsOrMemoryKeys() {
        assertThat(AgentImportJson.isSecretKey("openAIApiKey")).isTrue();
        assertThat(AgentImportJson.isSecretKey("credential")).isTrue();
        assertThat(AgentImportJson.isSecretKey("Authorization")).isTrue();
        assertThat(AgentImportJson.isSecretKey("client_secret")).isTrue();
        assertThat(AgentImportJson.isSecretKey("maxTokens")).isFalse();
        assertThat(AgentImportJson.isSecretKey("max_tokens")).isFalse();
        assertThat(AgentImportJson.isSecretKey("memoryKey")).isFalse();
        assertThat(AgentImportJson.isSecretKey(null)).isFalse();
    }

    @Test
    void credentialCountSkipsEmptyValuesAndNeverRecursesDeeplyOnTheStack() {
        assertThat(
                        AgentImportJson.countCredentialFields(
                                parse(
                                        "{\"a\":{\"credential\":\"\",\"apiKey\":null,\"b\":[{\"token\":\"t\"},{\"password\":\"p\"}]}}")))
                .isEqualTo(2);
        // 5,000 levels deep: the scan is iterative, so this cannot overflow the call stack
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("{\"n\":");
        }
        sb.append("1");
        for (int i = 0; i < 5000; i++) {
            sb.append("}");
        }
        tools.jackson.databind.ObjectMapper deep =
                com.agenticai.core.json.JsonMappers.withFactory(
                        tools.jackson.core.json.JsonFactory.builder()
                                .streamReadConstraints(
                                        tools.jackson.core.StreamReadConstraints.builder()
                                                .maxNestingDepth(10_000)
                                                .build())
                                .build());
        try {
            assertThat(AgentImportJson.countCredentialFields(deep.readTree(sb.toString())))
                    .isZero();
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- detector / source / providers
    // ---------------------------------------------------------------------

    @Test
    void detectorRecognisesEachFixtureShape() {
        assertThat(AgentImportDetector.detect(json("openai-assistant.json")))
                .contains(AgentImportSource.OPENAI_ASSISTANT);
        assertThat(AgentImportDetector.detect(json("openai-agents-sdk.json")))
                .contains(AgentImportSource.OPENAI_AGENTS);
        assertThat(AgentImportDetector.detect(json("flowise-tool-agent.json")))
                .contains(AgentImportSource.FLOWISE);
        assertThat(AgentImportDetector.detect(json("flowise-agentflow-v2.json")))
                .contains(AgentImportSource.FLOWISE);
        assertThat(AgentImportDetector.detect(parse("{\"id\":\"asst_1\"}")))
                .contains(AgentImportSource.OPENAI_ASSISTANT);
        assertThat(AgentImportDetector.detect(parse("{\"flowData\":\"{}\"}")))
                .contains(AgentImportSource.FLOWISE);
    }

    @Test
    void detectorStaysSilentWhenUnsure() {
        assertThat(AgentImportDetector.detect(parse("{\"hello\":1}"))).isEmpty();
        assertThat(AgentImportDetector.detect(parse("[]"))).isEmpty();
        assertThat(AgentImportDetector.detect(null)).isEmpty();
    }

    @Test
    void sourceParsingAcceptsAliasesAndAuto() {
        assertThat(AgentImportSource.parse("OpenAI_Assistant"))
                .contains(AgentImportSource.OPENAI_ASSISTANT);
        assertThat(AgentImportSource.parse("agent-builder"))
                .contains(AgentImportSource.OPENAI_AGENTS);
        assertThat(AgentImportSource.parse("flowise")).contains(AgentImportSource.FLOWISE);
        assertThat(AgentImportSource.parse("zapier")).isEmpty();
        assertThat(AgentImportSource.isAuto("AUTO")).isTrue();
        assertThat(AgentImportSource.isAuto(" ")).isTrue();
        assertThat(AgentImportSource.isAuto("flowise")).isFalse();
    }

    @Test
    void providerHintsMapToRealProvidersOnly() {
        assertThat(AgentImportProviders.fromHint("OpenAI")).contains(ProviderName.OPENAI);
        assertThat(AgentImportProviders.fromHint("google_vertex"))
                .contains(ProviderName.GOOGLE_VERTEX);
        assertThat(AgentImportProviders.fromHint("xai")).contains(ProviderName.GROK);
        assertThat(AgentImportProviders.fromHint("cohere")).isEmpty();
        assertThat(AgentImportProviders.fromHint(null)).isEmpty();
        assertThat(AgentImportProviders.flowiseProviderHint("chatOpenAI")).isEqualTo("openai");
        assertThat(AgentImportProviders.flowiseProviderHint("groqChat")).isEqualTo("groq");
        assertThat(AgentImportProviders.flowiseProviderHint("somethingElse")).isNull();
    }

    // ---- finalizer
    // -------------------------------------------------------------------------------------

    @Test
    void finalizerTruncatesLongInstructionsAndSaysSo() {
        AgentImportDraft d = new AgentImportDraft();
        d.name = "n";
        d.instructions = "x".repeat(AgentImportFinalizer.INSTRUCTIONS_MAX + 500);
        AgentImportFinalizer.finish(d);

        assertThat(d.instructions).hasSize(AgentImportFinalizer.INSTRUCTIONS_MAX);
        assertThat(d.report.toReport().warnings()).anyMatch(w -> w.contains("truncated"));
    }

    @Test
    void finalizerFlagsForeignTemplatePlaceholdersAndClampsSampling() {
        AgentImportDraft d = new AgentImportDraft();
        d.instructions = "Answer {{ question }} politely.";
        d.temperature = -0.5;
        d.topP = 3.0;
        AgentImportFinalizer.finish(d);

        assertThat(d.report.toReport().warnings()).anyMatch(w -> w.contains("placeholders"));
        assertThat(d.temperature).isEqualTo(0.0);
        assertThat(d.topP).isEqualTo(1.0);
    }

    @Test
    void reportCapsEachSectionAndSummarisesTheOverflow() {
        AgentImportReport r = new AgentImportReport();
        for (int i = 0; i < 250; i++) {
            r.unsupported("u" + i, "why");
        }
        assertThat(r.toReport().unsupported()).hasSize(AgentImportReport.MAX_ITEMS_PER_LIST);
        assertThat(r.toReport().warnings()).anyMatch(w -> w.contains("150 further report entries"));
    }
}
