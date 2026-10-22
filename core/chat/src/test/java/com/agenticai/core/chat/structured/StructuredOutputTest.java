package com.agenticai.core.chat.structured;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;

// S2187: every test lives in a @Nested class below; Sonar only descends into @Nested
// when junit-jupiter-api is on its analysis classpath.
@SuppressWarnings("java:S2187")
class StructuredOutputTest {

    private final ObjectMapper mapper = new ObjectMapper();

    record Verdict(String category, int score, List<String> tags) {}

    @Nested
    class StripCodeFence {

        @Test
        void removesFenceWithLanguageTag() {
            assertThat(StructuredOutput.stripCodeFence("```json\n{\"a\":1}\n```"))
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        void removesFenceWithoutLanguageTagAndClosingOnSameLine() {
            assertThat(StructuredOutput.stripCodeFence("```\n{\"a\":1}```")).isEqualTo("{\"a\":1}");
        }

        @Test
        void dropsChatterAfterTheClosingFence() {
            assertThat(StructuredOutput.stripCodeFence("```json\n{\"a\":1}\n```\nHope this helps!"))
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        void handlesSingleLineFence() {
            assertThat(StructuredOutput.stripCodeFence("```json{\"a\":1}```"))
                    .isEqualTo("{\"a\":1}");
            assertThat(StructuredOutput.stripCodeFence("```{\"a\":1}```")).isEqualTo("{\"a\":1}");
        }

        @Test
        void removesATrailingFenceOnly() {
            assertThat(StructuredOutput.stripCodeFence("{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        }

        @Test
        void trimsUnfencedTextAndToleratesNull() {
            assertThat(StructuredOutput.stripCodeFence("  {\"a\":1}  ")).isEqualTo("{\"a\":1}");
            assertThat(StructuredOutput.stripCodeFence(null)).isEmpty();
        }
    }

    @Nested
    class Extraction {

        @Test
        void wholeDocumentIsReturnedAsIs() {
            assertThat(StructuredOutput.extractJson("{\"a\":1}")).contains("{\"a\":1}");
            assertThat(StructuredOutput.extractJson("[1,2]")).contains("[1,2]");
        }

        @Test
        void proseBeforeAndAfterIsIgnored() {
            assertThat(
                            StructuredOutput.extractJsonObject(
                                    "Sure! Here you go: {\"a\":1} Let me know."))
                    .contains("{\"a\":1}");
        }

        @Test
        void aSecondBraceSpanAfterTheJsonDoesNotBreakIt() {
            // The old first-{ to last-} slice produced '{"a":1} if you need {more}' - unparseable.
            assertThat(StructuredOutput.extractJsonObject("{\"a\":1} if you need {more}, ask"))
                    .contains("{\"a\":1}");
        }

        @Test
        void bracesAndQuotesInsideStringsAreNotStructure() {
            String json = "{\"code\":\"if (x) { return \\\"}\\\"; }\",\"n\":[1,{\"b\":\"]\"}]}";
            assertThat(StructuredOutput.extractJsonObject("Result:\n" + json + "\nDone."))
                    .contains(json);
        }

        @Test
        void aNonJsonBraceGroupInProseIsSkipped() {
            assertThat(
                            StructuredOutput.extractJsonObject(
                                    "Use a {placeholder} like this: {\"a\":true}"))
                    .contains("{\"a\":true}");
        }

        @Test
        void fencedJsonAfterProseIsFound() {
            String reply = "Here is the plan:\n```json\n{\"steps\":[\"a\",\"b\"]}\n```\nThanks";
            assertThat(StructuredOutput.extractJsonObject(reply))
                    .contains("{\"steps\":[\"a\",\"b\"]}");
        }

        @Test
        void unclosedFenceWhoseJsonContainsBackticksStillParses() {
            String json = "{\"md\":\"use ``` for code\"}";
            assertThat(StructuredOutput.extractJsonObject("```json\n" + json)).contains(json);
        }

        @Test
        void arrayShapeFindsTheArray() {
            assertThat(StructuredOutput.extractJsonArray("Triples: [{\"s\":\"x\"}] end"))
                    .contains("[{\"s\":\"x\"}]");
        }

        @Test
        void wrongShapedDocumentIsSearchedForTheWantedShape() {
            assertThat(StructuredOutput.extractJsonArray("{\"triples\":[{\"s\":\"x\"}]}"))
                    .contains("[{\"s\":\"x\"}]");
            assertThat(StructuredOutput.extractJsonObject("[1,2]")).isEmpty();
        }

        @Test
        void unspecifiedShapePrefersAnObjectOverACitationStyleArray() {
            assertThat(StructuredOutput.extractJson("As noted in [1], the answer is {\"a\":1}"))
                    .contains("{\"a\":1}");
            assertThat(StructuredOutput.extractJson("Only a list here: [1, 2]")).contains("[1, 2]");
        }

        @Test
        void truncatedJsonIsNotFound() {
            assertThat(StructuredOutput.extractJsonObject("{\"a\": [1, 2")).isEmpty();
        }

        @Test
        void mismatchedBracketsAreRejected() {
            assertThat(JsonLocator.matchingClose("{[}]", 0)).isEqualTo(-1);
            assertThat(JsonLocator.matchingClose("{[]}", 0)).isEqualTo(3);
        }

        @Test
        void deepNestingGrowsTheStack() {
            String deep = "[".repeat(100) + "]".repeat(100);
            assertThat(StructuredOutput.extractJsonArray(deep)).contains(deep);
        }

        @Test
        void emptyAndNullAndProseYieldEmpty() {
            assertThat(StructuredOutput.extractJson(null)).isEmpty();
            assertThat(StructuredOutput.extractJson("   ")).isEmpty();
            assertThat(StructuredOutput.extractJson("not json at all")).isEmpty();
        }

        @Test
        void scalarPrefixWithTrailingTextIsNotTakenAsTheDocument() {
            // Plain Jackson readTree("true story {...}") returns BooleanNode(true) and ignores the
            // rest.
            assertThat(StructuredOutput.extractJson("true story: {\"a\":1}")).contains("{\"a\":1}");
        }

        @Test
        void pathologicalInputTerminates() {
            String noise = "{ x ".repeat(5_000);
            assertThat(StructuredOutput.extractJsonObject(noise)).isEmpty();
        }
    }

    @Nested
    class TreeParsing {

        @Test
        void readTreeAcceptsAnyWholeDocument() {
            assertThat(StructuredOutput.readTree(mapper, "[1]").orElseThrow().isArray()).isTrue();
            assertThat(StructuredOutput.readTree(mapper, "42").orElseThrow().asInt()).isEqualTo(42);
        }

        @Test
        void readObjectOnlyReturnsObjects() {
            assertThat(
                            StructuredOutput.readObject(mapper, "```json\n{\"a\":1}\n```")
                                    .orElseThrow()
                                    .path("a")
                                    .asInt())
                    .isEqualTo(1);
            assertThat(StructuredOutput.readObject(mapper, "[1,2]")).isEmpty();
        }

        @Test
        void readArrayOnlyReturnsArrays() {
            assertThat(StructuredOutput.readArray(mapper, "ok: [1,2]").orElseThrow().size())
                    .isEqualTo(2);
            assertThat(StructuredOutput.readArray(mapper, "{\"a\":1}")).isEmpty();
        }

        @Test
        void requireObjectThrowsWithTheRawText() {
            assertThatThrownBy(() -> StructuredOutput.requireObject(mapper, "nope"))
                    .isInstanceOf(StructuredOutputException.class)
                    .satisfies(
                            e ->
                                    assertThat(((StructuredOutputException) e).rawText())
                                            .isEqualTo("nope"));
        }
    }

    @Nested
    class TypedParsing {

        @Test
        void bindsToARecord() {
            Verdict v =
                    StructuredOutput.parse(
                            mapper,
                            "```json\n{\"category\":\"ok\",\"score\":3,\"tags\":[\"x\"]}\n```",
                            Verdict.class);
            assertThat(v).isEqualTo(new Verdict("ok", 3, List.of("x")));
        }

        @Test
        void bindsGenericTargets() {
            List<Map<String, Integer>> list =
                    StructuredOutput.parse(
                            mapper,
                            "Result: [{\"a\":1}]",
                            new TypeReference<List<Map<String, Integer>>>() {});
            assertThat(list).containsExactly(Map.of("a", 1));
        }

        @Test
        void collectionTargetsAreSearchedAsArraysAndObjectTargetsAsObjects() {
            // Without the target-type shape, the object-first scan would hand {"a":1} to a List
            // binding.
            List<Map<String, Integer>> list =
                    StructuredOutput.parse(
                            mapper,
                            "Result: [{\"a\":1}] (1 item)",
                            new TypeReference<List<Map<String, Integer>>>() {});
            assertThat(list).containsExactly(Map.of("a", 1));
            Verdict v =
                    StructuredOutput.parse(
                            mapper, "Per [1]: {\"category\":\"c\",\"score\":1}", Verdict.class);
            assertThat(v.category()).isEqualTo("c");
            String[] arr =
                    StructuredOutput.parse(
                            mapper, "Tags: {not json} [\"x\",\"y\"]", String[].class);
            assertThat(arr).containsExactly("x", "y");
        }

        @Test
        void noJsonIsAClearError() {
            assertThatThrownBy(
                            () ->
                                    StructuredOutput.parse(
                                            mapper, "I cannot help with that.", Verdict.class))
                    .isInstanceOf(StructuredOutputException.class)
                    .hasMessageContaining("did not contain valid JSON")
                    .hasMessageContaining("Verdict");
        }

        @Test
        void emptyReplyIsAClearError() {
            assertThatThrownBy(() -> StructuredOutput.parse(mapper, "", Verdict.class))
                    .isInstanceOf(StructuredOutputException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        void bindFailureCarriesJacksonsReasonAndCause() {
            assertThatThrownBy(
                            () ->
                                    StructuredOutput.parse(
                                            mapper, "{\"score\":\"high\"}", Verdict.class))
                    .isInstanceOf(StructuredOutputException.class)
                    .hasMessageContaining("does not match the expected Verdict shape")
                    .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }

        @Test
        void usesTheCallersMapperConfiguration() {
            ObjectMapper lenient =
                    new ObjectMapper()
                            .configure(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_UNKNOWN_PROPERTIES,
                                    false);
            Verdict v =
                    StructuredOutput.parse(
                            lenient, "{\"category\":\"ok\",\"extra\":1}", Verdict.class);
            assertThat(v.category()).isEqualTo("ok");
            assertThatThrownBy(
                            () ->
                                    StructuredOutput.parse(
                                            mapper,
                                            "{\"category\":\"ok\",\"extra\":1}",
                                            Verdict.class))
                    .isInstanceOf(StructuredOutputException.class);
        }
    }

    @Nested
    class Repair {

        @Test
        void validFirstReplyNeverCallsRepair() {
            List<String> calls = new ArrayList<>();
            Verdict v =
                    StructuredOutput.parseWithRepair(
                            mapper,
                            "{\"category\":\"a\",\"score\":1}",
                            Verdict.class,
                            (prev, err) -> {
                                calls.add(prev);
                                return "unused";
                            });
            assertThat(v.category()).isEqualTo("a");
            assertThat(calls).isEmpty();
        }

        @Test
        void repairsOnceWithTheErrorAndPreviousReply() {
            List<String> seen = new ArrayList<>();
            Verdict v =
                    StructuredOutput.parseWithRepair(
                            mapper,
                            "oops",
                            Verdict.class,
                            (prev, err) -> {
                                seen.add(prev);
                                seen.add(err.getMessage());
                                return "{\"category\":\"fixed\",\"score\":2}";
                            });
            assertThat(v.category()).isEqualTo("fixed");
            assertThat(seen.get(0)).isEqualTo("oops");
            assertThat(seen.get(1)).contains("did not contain valid JSON");
        }

        @Test
        void secondFailureThrowsWithFirstSuppressed() {
            int[] calls = {0};
            assertThatThrownBy(
                            () ->
                                    StructuredOutput.parseWithRepair(
                                            mapper,
                                            "oops",
                                            Verdict.class,
                                            (prev, err) -> {
                                                calls[0]++;
                                                return "still not json";
                                            }))
                    .isInstanceOf(StructuredOutputException.class)
                    .satisfies(e -> assertThat(e.getSuppressed()).hasSize(1));
            assertThat(calls[0]).isEqualTo(1);
        }

        @Test
        void nullRepairRethrows() {
            assertThatThrownBy(
                            () ->
                                    StructuredOutput.parseWithRepair(
                                            mapper, "oops", Verdict.class, null))
                    .isInstanceOf(StructuredOutputException.class);
        }

        @Test
        void repairFunctionFailuresPropagateUnwrapped() {
            IllegalStateException budget = new IllegalStateException("budget exhausted");
            assertThatThrownBy(
                            () ->
                                    StructuredOutput.parseWithRepair(
                                            mapper,
                                            "oops",
                                            Verdict.class,
                                            (p, e) -> {
                                                throw budget;
                                            }))
                    .isSameAs(budget);
        }

        @Test
        void treeParserVariantWorks() {
            JsonNode node =
                    StructuredOutput.parseWithRepair(
                            "nope",
                            text -> StructuredOutput.requireObject(mapper, text),
                            (p, e) -> "{\"ok\":true}");
            assertThat(node.path("ok").asBoolean()).isTrue();
        }

        @Test
        void repairPromptCarriesTheErrorAndInstructions() {
            String prompt =
                    StructuredOutput.repairPrompt(
                            new StructuredOutputException(
                                    "The reply did not contain valid JSON.", "x"),
                            "SCHEMA HERE");
            assertThat(prompt)
                    .contains("did not contain valid JSON")
                    .contains("ONLY the corrected JSON")
                    .contains("SCHEMA HERE")
                    .doesNotContain("\"x\"");
            assertThat(
                            StructuredOutput.repairPrompt(
                                    new StructuredOutputException("bad", null), null))
                    .doesNotContain("null");
        }
    }

    @Nested
    class FormatInstructions {

        @Test
        void usesSpringAiSchemaForTheType() {
            String format = StructuredOutput.formatInstructions(Verdict.class);
            assertThat(format)
                    .contains("JSON")
                    .contains("\"category\"")
                    .contains("\"score\"")
                    .contains("\"tags\"");
            String schema = StructuredOutput.jsonSchema(Verdict.class);
            assertThat(schema).contains("\"object\"").contains("\"score\"");
        }
    }

    @Nested
    class JsonMode {

        @Test
        void openAiGetsJsonObjectResponseFormatOnACopy() {
            OpenAiChatOptions original =
                    OpenAiChatOptions.builder().model("gpt-x").temperature(0.2).build();
            OpenAiChatOptions json = StructuredOutput.withJsonMode(original);
            assertThat(json).isNotSameAs(original);
            assertThat(json.getResponseFormat().getType())
                    .isEqualTo(ResponseFormat.Type.JSON_OBJECT);
            assertThat(json.getModel()).isEqualTo("gpt-x");
            assertThat(json.getTemperature()).isEqualTo(0.2);
            assertThat(original.getResponseFormat()).isNull();
            assertThat(StructuredOutput.supportsJsonMode(original)).isTrue();
        }

        @Test
        void existingOpenAiStructuredFormatIsKept() {
            OpenAiChatOptions original =
                    OpenAiChatOptions.builder()
                            .responseFormat(
                                    ResponseFormat.builder()
                                            .type(ResponseFormat.Type.JSON_SCHEMA)
                                            .jsonSchema("{\"type\":\"object\"}")
                                            .build())
                            .build();
            assertThat(StructuredOutput.withJsonMode(original)).isSameAs(original);
        }

        @Test
        void geminiGetsJsonMimeTypeOnACopy() {
            GoogleGenAiChatOptions original =
                    GoogleGenAiChatOptions.builder().model("gemini-x").build();
            GoogleGenAiChatOptions json = StructuredOutput.withJsonMode(original);
            assertThat(json).isNotSameAs(original);
            assertThat(json.getResponseMimeType()).isEqualTo("application/json");
            assertThat(json.getModel()).isEqualTo("gemini-x");
            assertThat(original.getResponseMimeType()).isNotEqualTo("application/json");
        }

        @Test
        void otherProvidersAreANoOp() {
            AnthropicChatOptions anthropic =
                    AnthropicChatOptions.builder().model("claude-x").build();
            assertThat(StructuredOutput.withJsonMode(anthropic)).isSameAs(anthropic);
            assertThat(StructuredOutput.supportsJsonMode(anthropic)).isFalse();
            ChatOptions generic = ChatOptions.builder().model("m").build();
            assertThat(StructuredOutput.withJsonMode(generic)).isSameAs(generic);
            assertThat(StructuredOutput.<ChatOptions>withJsonMode(null)).isNull();
            assertThat(StructuredOutput.supportsJsonMode(null)).isFalse();
        }
    }
}
