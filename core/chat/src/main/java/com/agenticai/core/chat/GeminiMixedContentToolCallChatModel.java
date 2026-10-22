package com.agenticai.core.chat;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;

/**
 * Works around spring-projects/spring-ai#5466: {@code GoogleGenAiChatModel
 * #responseCandidateToGeneration} only recognizes a candidate as a tool call when {@code allMatch}
 * finds every {@link Part} in it is a functionCall. Gemini routinely emits a lead-in text/thought
 * {@link Part} alongside the functionCall {@link Part} in the same candidate, so {@code allMatch}
 * fails and upstream silently falls through to its text-only branch - the functionCall is discarded
 * outright, no exception, no log line, and the turn resolves as an ordinary text response (the
 * model "explaining" it can't do the thing it just tried to call a tool for). Confirmed via the
 * upstream 1.1.7 jar's bytecode (allMatch at the Stream.allMatch call site in
 * responseCandidateToGeneration) and the upstream issue thread.
 *
 * <p><b>Spring AI 2.0.1 (ADR-0012 P3).</b> Upstream now uses {@code anyMatch} too, but still drops
 * the lead-in text of a mixed candidate ({@code content("")}) and throws on a candidate with no
 * content at all, so this override is still needed for both. Its protected hook survived the 2.0
 * rework (the tool loop moved out of the model into {@code ToolCallingAdvisor}, this method did
 * not) and is still called virtually from both the blocking and the streaming path. It now also
 * carries the two metadata additions 2.0 made to the base implementation - {@code isThought} on
 * text parts (2.0's {@code MessageAggregator} uses it to keep thought text out of the aggregated
 * answer) and {@code serverSideToolInvocations} - so a turn behaves as the base class would, plus
 * the two fixes above.
 *
 * <p>This subclass overrides that one protected method with an {@code anyMatch}-based version
 * instead: any functionCall part in the candidate makes it a tool-call generation, with the
 * functionCall parts feeding {@code toolCalls} and every other part's text concatenated into the
 * assistant message's content (instead of the content being dropped). A candidate with no
 * functionCall parts at all still takes the original per-part streaming-text path unchanged.
 *
 * <p>The call site inside {@code GoogleGenAiChatModel} invokes this method via a virtual method
 * reference (confirmed via the jar's constant pool: {@code REF_invokeVirtual
 * ...responseCandidateToGeneration}), so overriding it here is honored by both the streaming and
 * non-streaming code paths without needing to touch anything else in the base class.
 *
 * <p>This is unrelated to the separate thought_signature-missing 400 that {@code
 * AgentChatService}'s {@code isKnownThoughtSignatureBug} retry handles - that one fires on the
 * multi-turn follow-up request, after a tool call already succeeded; this fixes the tool call never
 * being attempted in the first place.
 */
public class GeminiMixedContentToolCallChatModel extends GoogleGenAiChatModel {

    /**
     * @param retry the bounded policy from {@link LlmModelSupport#retry()} - never Spring AI's
     *     {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} (10 attempts, backoff up to 3 minutes), which
     *     used to be hard-wired here.
     */
    public GeminiMixedContentToolCallChatModel(
            Client genAiClient,
            GoogleGenAiChatOptions defaultOptions,
            ToolCallingManager toolCallingManager,
            LlmRetry retry,
            ObservationRegistry observationRegistry) {
        super(
                genAiClient,
                defaultOptions,
                toolCallingManager,
                retry.forSpringAi(),
                observationRegistry);
    }

    /** Convenience for code without a context (tests): {@link LlmModelSupport#defaultRetry()}. */
    public GeminiMixedContentToolCallChatModel(
            Client genAiClient,
            GoogleGenAiChatOptions defaultOptions,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry) {
        this(
                genAiClient,
                defaultOptions,
                toolCallingManager,
                LlmModelSupport.defaultRetry(),
                observationRegistry);
    }

    @Override
    protected List<Generation> responseCandidateToGeneration(Candidate candidate) {
        int candidateIndex = candidate.index().orElse(0);
        FinishReason candidateFinishReason =
                candidate.finishReason().orElse(new FinishReason(FinishReason.Known.STOP));

        Map<String, Object> messageMetadata = new HashMap<>();
        messageMetadata.put("candidateIndex", candidateIndex);
        messageMetadata.put("finishReason", candidateFinishReason);

        List<Part> parts = candidate.content().flatMap(Content::parts).orElse(List.of());

        List<byte[]> thoughtSignatures =
                parts.stream()
                        .filter(part -> part.thoughtSignature().isPresent())
                        .map(part -> part.thoughtSignature().get())
                        .toList();
        if (!thoughtSignatures.isEmpty()) {
            messageMetadata.put("thoughtSignatures", thoughtSignatures);
        }
        List<Map<String, Object>> serverSideToolInvocations = serverSideToolInvocations(parts);
        if (!serverSideToolInvocations.isEmpty()) {
            messageMetadata.put("serverSideToolInvocations", serverSideToolInvocations);
        }

        ChatGenerationMetadata chatGenerationMetadata =
                ChatGenerationMetadata.builder()
                        .finishReason(candidateFinishReason.toString())
                        .build();

        // Empty-candidate fix (2026-08-10): a Gemini candidate with NO parts at all
        // (finishReason SAFETY/RECITATION/MAX_TOKENS/MALFORMED_FUNCTION_CALL - the
        // model emitted nothing) used to fall through to the empty-parts branch
        // below, which maps an empty list to an empty list of Generations. Spring
        // AI's ChatResponse#getResult() returns null for an empty generations list
        // (CollectionUtils.isEmpty check, confirmed against the installed
        // spring-ai-model jar), .content()'s stream then filters the resulting ""
        // via StringUtils::hasLength, and the Flux completes with ZERO elements and
        // NO exception anywhere in that chain - a live incident traced this exact
        // path to "turn completed with 0 tokens, no error" (AgentChatService's
        // EMPTY_REPLY_FALLBACK backstop), with candidateFinishReason - already
        // correctly computed above - silently discarded before it could explain
        // why. Emitting one Generation here instead (empty text, but the SAME real
        // chatGenerationMetadata/finishReason every other branch already attaches)
        // costs nothing for the ordinary non-empty case and gives any future
        // finishReason-aware handling upstream something real to inspect instead of
        // an empty list it can't distinguish from "no response at all."
        if (parts.isEmpty()) {
            return List.of(
                    new Generation(
                            AssistantMessage.builder()
                                    .content("")
                                    .properties(messageMetadata)
                                    .build(),
                            chatGenerationMetadata));
        }

        boolean hasFunctionCall = parts.stream().anyMatch(part -> part.functionCall().isPresent());

        if (!hasFunctionCall) {
            List<Generation> generations =
                    parts.stream()
                            .filter(
                                    part ->
                                            part.toolCall().isEmpty()
                                                    && part.toolResponse().isEmpty())
                            .map(
                                    part -> {
                                        Map<String, Object> partMetadata =
                                                new HashMap<>(messageMetadata);
                                        partMetadata.put("isThought", part.thought().orElse(false));
                                        return AssistantMessage.builder()
                                                .content(part.text().orElse(""))
                                                .properties(partMetadata)
                                                .build();
                                    })
                            .map(
                                    assistantMessage ->
                                            new Generation(
                                                    assistantMessage, chatGenerationMetadata))
                            .toList();
            if (generations.isEmpty()) {
                // Every part was a server-side tool invocation: one empty-text generation carrying
                // the metadata.
                return List.of(
                        new Generation(
                                AssistantMessage.builder()
                                        .content("")
                                        .properties(messageMetadata)
                                        .build(),
                                chatGenerationMetadata));
            }
            return generations;
        }

        List<AssistantMessage.ToolCall> assistantToolCalls =
                parts.stream()
                        .filter(part -> part.functionCall().isPresent())
                        .map(
                                part -> {
                                    FunctionCall functionCall = part.functionCall().get();
                                    String functionName = functionCall.name().orElse("");
                                    String functionArguments =
                                            mapToJson(functionCall.args().orElse(Map.of()));
                                    return new AssistantMessage.ToolCall(
                                            "", "function", functionName, functionArguments);
                                })
                        .toList();

        String textContent =
                parts.stream()
                        .filter(part -> part.functionCall().isEmpty())
                        .map(part -> part.text().orElse(""))
                        .collect(Collectors.joining());

        AssistantMessage assistantMessage =
                AssistantMessage.builder()
                        .content(textContent)
                        .properties(messageMetadata)
                        .toolCalls(assistantToolCalls)
                        .build();

        return List.of(new Generation(assistantMessage, chatGenerationMetadata));
    }

    private static List<Map<String, Object>> serverSideToolInvocations(List<Part> parts) {
        List<Map<String, Object>> invocations = new ArrayList<>();
        for (Part part : parts) {
            if (part.toolCall().isPresent()) {
                com.google.genai.types.ToolCall tc = part.toolCall().get();
                Map<String, Object> inv = new HashMap<>();
                inv.put("type", "toolCall");
                inv.put("id", tc.id().orElse(""));
                inv.put("toolType", tc.toolType().map(Object::toString).orElse(""));
                inv.put("args", tc.args().orElse(Map.of()));
                invocations.add(inv);
            }
            if (part.toolResponse().isPresent()) {
                com.google.genai.types.ToolResponse tr = part.toolResponse().get();
                Map<String, Object> inv = new HashMap<>();
                inv.put("type", "toolResponse");
                inv.put("id", tr.id().orElse(""));
                inv.put("toolType", tr.toolType().map(Object::toString).orElse(""));
                inv.put("response", tr.response().orElse(Map.of()));
                invocations.add(inv);
            }
        }
        return invocations;
    }

    private static String mapToJson(Map<String, Object> map) {
        try {
            return SchemaJson.write(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert map to JSON", e);
        }
    }
}
