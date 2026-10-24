package com.agenticai.core.chat;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import io.micrometer.observation.ObservationRegistry;
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
 * #responseCandidateToGeneration} only recognizes a candidate as a tool call
 * when {@code allMatch} finds every {@link Part} in it is a functionCall.
 * Gemini routinely emits a lead-in text/thought {@link Part} alongside the
 * functionCall {@link Part} in the same candidate, so {@code allMatch} fails
 * and upstream silently falls through to its text-only branch - the
 * functionCall is discarded outright, no exception, no log line, and the
 * turn resolves as an ordinary text response (the model "explaining" it
 * can't do the thing it just tried to call a tool for). Confirmed via the
 * upstream 1.1.7 jar's bytecode (allMatch at the Stream.allMatch call site in
 * responseCandidateToGeneration) and the upstream issue thread.
 *
 * <p>This subclass overrides that one protected method with an
 * {@code anyMatch}-based version instead: any functionCall part in the
 * candidate makes it a tool-call generation, with the functionCall parts
 * feeding {@code toolCalls} and every other part's text concatenated into
 * the assistant message's content (instead of the content being dropped).
 * A candidate with no functionCall parts at all still takes the original
 * per-part streaming-text path unchanged.
 *
 * <p>The call site inside {@code GoogleGenAiChatModel} invokes this method
 * via a virtual method reference (confirmed via the jar's constant pool:
 * {@code REF_invokeVirtual ...responseCandidateToGeneration}), so overriding
 * it here is honored by both the streaming and non-streaming code paths
 * without needing to touch anything else in the base class.
 *
 * <p><b>Thought signatures.</b> The follow-up request after a tool call is
 * refused by Gemini ("Function call is missing a thought_signature") unless
 * the model's own {@code functionCall} part goes back with the signature
 * Gemini put on it. Spring AI 1.1.8 keeps the reply's signatures in the
 * {@code thoughtSignatures} message property and, when it rebuilds the
 * model's turn ({@code GoogleGenAiChatModel#messageToGeminiParts}, checked
 * against the 1.1.8 jar), writes the text as one unsigned part and then
 * takes one entry off that list for each function-call part, in order. So
 * the list must hold the function-call parts' signatures only, lined up
 * with the tool calls - see {@link #replayableThoughtSignatures}. It used
 * to hold every part's, which shifted by one whenever a lead-in text part
 * was signed, and the function call went back with the text part's
 * signature. The turn-level fallback for a provider that still refuses is
 * {@code ThoughtSignatureDegrade} (a visible reply written without tools).
 */
public class GeminiMixedContentToolCallChatModel extends GoogleGenAiChatModel {

    /**
     * @param retry the bounded policy from {@link LlmModelSupport#retry()} -
     *     never Spring AI's {@code RetryUtils.DEFAULT_RETRY_TEMPLATE} (10 attempts, backoff
     *     up to 3 minutes), which used to be hard-wired here.
     */
    public GeminiMixedContentToolCallChatModel(
        Client genAiClient, GoogleGenAiChatOptions defaultOptions,
        ToolCallingManager toolCallingManager, LlmRetry retry, ObservationRegistry observationRegistry
    ) {
        super(genAiClient, defaultOptions, toolCallingManager, retry.forSpringAi(), observationRegistry);
    }

    /** Convenience for code without a context (tests): {@link LlmModelSupport#defaultRetry()}. */
    public GeminiMixedContentToolCallChatModel(
        Client genAiClient, GoogleGenAiChatOptions defaultOptions,
        ToolCallingManager toolCallingManager, ObservationRegistry observationRegistry
    ) {
        this(genAiClient, defaultOptions, toolCallingManager, LlmModelSupport.defaultRetry(), observationRegistry);
    }

    @Override
    protected List<Generation> responseCandidateToGeneration(Candidate candidate) {
        int candidateIndex = candidate.index().orElse(0);
        FinishReason candidateFinishReason = candidate.finishReason().orElse(new FinishReason(FinishReason.Known.STOP));

        Map<String, Object> messageMetadata = new HashMap<>();
        messageMetadata.put("candidateIndex", candidateIndex);
        messageMetadata.put("finishReason", candidateFinishReason);

        List<Part> parts = candidate.content().flatMap(Content::parts).orElse(List.of());

        List<byte[]> thoughtSignatures = replayableThoughtSignatures(parts);
        if (!thoughtSignatures.isEmpty()) {
            messageMetadata.put("thoughtSignatures", thoughtSignatures);
        }

        ChatGenerationMetadata chatGenerationMetadata = ChatGenerationMetadata.builder()
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
        // empty-reply finish notice backstop), with candidateFinishReason - already
        // correctly computed above - silently discarded before it could explain
        // why. Emitting one Generation here instead (empty text, but the SAME real
        // chatGenerationMetadata/finishReason every other branch already attaches)
        // costs nothing for the ordinary non-empty case and gives any future
        // finishReason-aware handling upstream something real to inspect instead of
        // an empty list it can't distinguish from "no response at all."
        if (parts.isEmpty()) {
            return List.of(new Generation(
                AssistantMessage.builder().content("").properties(messageMetadata).build(),
                chatGenerationMetadata
            ));
        }

        boolean hasFunctionCall = parts.stream().anyMatch(part -> part.functionCall().isPresent());

        if (!hasFunctionCall) {
            return parts.stream()
                .map(part -> AssistantMessage.builder()
                    .content(part.text().orElse(""))
                    .properties(messageMetadata)
                    .build())
                .map(assistantMessage -> new Generation(assistantMessage, chatGenerationMetadata))
                .toList();
        }

        List<AssistantMessage.ToolCall> assistantToolCalls = parts.stream()
            .filter(part -> part.functionCall().isPresent())
            .map(part -> {
                FunctionCall functionCall = part.functionCall().get();
                String functionName = functionCall.name().orElse("");
                String functionArguments = mapToJson(functionCall.args().orElse(Map.of()));
                // Gemini function calls carry no id, but every consumer downstream needs one: the
                // result must be paired with its call, and the hosted agent-turn endpoint rejects a
                // tool message without a toolCallId. One is made up here, once, and travels with the
                // call (a blank id here used to make the client's next request a 400).
                String functionCallId = ToolCallIds.ensure(functionCall.id().orElse(null));
                return new AssistantMessage.ToolCall(functionCallId, "function", functionName, functionArguments);
            })
            .toList();

        String textContent = parts.stream()
            .filter(part -> part.functionCall().isEmpty())
            .map(part -> part.text().orElse(""))
            .collect(Collectors.joining());

        AssistantMessage assistantMessage = AssistantMessage.builder()
            .content(textContent)
            .properties(messageMetadata)
            .toolCalls(assistantToolCalls)
            .build();

        return List.of(new Generation(assistantMessage, chatGenerationMetadata));
    }

    /**
     * The signatures to send back with this reply, in the form Spring AI's request conversion
     * ({@code GoogleGenAiChatModel#messageToGeminiParts}) consumes them: it writes the reply's text as
     * one unsigned part, then takes one entry off this list for each function-call part, in order.
     * Gemini signs the part it reasoned about - the function call, and for a parallel batch only the
     * first - and may also sign a lead-in text or thought part. A list of every part's signature, as
     * this used to be, therefore shifted by one whenever a text part was signed: the function call went
     * back carrying the text part's signature and Gemini refused it ("Function call is missing a
     * thought_signature").
     *
     * <p>So the list holds the function-call parts' signatures only. It cannot express "this call has
     * none but the next has one" (nothing can be put in a slot to mean absent), so it stops at the first
     * unsigned function call that is followed by a signed one: a later call's signature is never handed to
     * an earlier call. Gemini requires the signature on the first call of a step, which is what a leading
     * run keeps.
     */
    static List<byte[]> replayableThoughtSignatures(List<Part> parts) {
        List<byte[]> signatures = new java.util.ArrayList<>();
        boolean gap = false;
        for (Part part : parts) {
            if (part.functionCall().isEmpty()) {
                continue;
            }
            if (part.thoughtSignature().isEmpty()) {
                gap = true;
            } else if (!gap) {
                signatures.add(part.thoughtSignature().get());
            }
        }
        return signatures;
    }

    private static String mapToJson(Map<String, Object> map) {
        try {
            return SchemaJson.write(map);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert map to JSON", e);
        }
    }
}
