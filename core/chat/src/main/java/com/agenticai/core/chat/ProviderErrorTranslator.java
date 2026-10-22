package com.agenticai.core.chat;

import com.google.genai.errors.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Best-effort translation of the exceptions Spring AI's model clients throw on non-2xx upstream
 * responses into a clean {@link UpstreamProviderException}.
 *
 * <p>Two distinct exception shapes are handled, because the underlying HTTP error handling differs
 * by provider:
 *
 * <ul>
 *   <li>AnthropicApi (and some other Spring AI clients) wrap non-2xx responses into a plain
 *       RuntimeException with a consistent "Response exception, Status: [&lt;status&gt;],
 *       Body:[&lt;body&gt;]" message shape - handled by the regex fallback below.
 *   <li>OpenAiApi (used directly, with a custom baseUrl, for OpenRouter/Grok - see
 *       OpenRouterAgentChatClientFactory/GrokAgentChatClientFactory) does not wrap errors at all:
 *       it lets the raw {@link WebClientResponseException} propagate, whose {@code getMessage()} is
 *       just "&lt;status&gt; &lt;reason&gt; from &lt;method&gt; &lt;uri&gt;" - no body. Confirmed
 *       in production: a real OpenRouter 400 surfaced as "The LLM provider call failed: 400 Bad
 *       Request from POST https://openrouter.ai/api/v1/chat/completions (upstream status 502)" -
 *       the regex didn't match, so the actual reason (invalid model id, insufficient credits, etc.,
 *       which OpenRouter puts in the JSON body) was silently discarded and the status fell back to
 *       the generic 502 default. WebClientResponseException carries the real body via {@link
 *       WebClientResponseException#getResponseBodyAsString()} even though getMessage() doesn't
 *       include it, so that type is checked first.
 * </ul>
 *
 * <p>Neither branch is guaranteed stable across Spring AI versions (as Stage 3's AnthropicApi
 * constructor change already demonstrated), so both are defensive best-effort parses. Never throws
 * while translating; falls back to a generic message if neither shape matches or the body isn't
 * parseable JSON.
 *
 * <p>Direct feedback ("I want to display the proper meaningful messages to consumer, not raw http
 * responses...even if there is an issue from llm response - it should be read properly and
 * displayed the right message") - every branch below now does two things, not one: it still builds
 * the full technical message (status + real provider reason) for {@link
 * UpstreamProviderException#getMessage()}/server logs, AND it runs {@link #classify} over that same
 * detail to build a short, plain-language {@link UpstreamProviderException#getConsumerMessage()}
 * with no HTTP status codes, provider error codes, or raw JSON in it - that second one is what
 * actually reaches a tenant's screen (see {@code GlobalExceptionHandler #handleUpstreamProvider}).
 *
 * <p>Follow-up direct feedback ("this message is too generic... display the actual underlying
 * error/reason returned by the provider whenever available... error code, provider response
 * message, timeout information, rate limit issues, authentication failures, or validation errors")
 * - a UI bug report showed the literal text "The LLM provider call failed: Failed to generate
 * content", which turned out to be Spring AI's Gemini client ({@code GoogleGenAiChatModel})
 * wrapping EVERY failure - auth, 404 model, quota, rate limit, malformed request - in the exact
 * same {@code new RuntimeException("Failed to generate content", e)}, discarding the real cause's
 * type and message from {@link Throwable#getMessage()} while still chaining it via {@link
 * Throwable#getCause()}. The old code below never called {@code getCause()} anywhere, so that real
 * detail - and the real HTTP status needed for {@link #classify} to pick the right consumer message
 * - was silently thrown away every time. Fixed by: (1) walking the FULL cause chain (not just the
 * outer exception) for both {@link WebClientResponseException} and the Google GenAI SDK's own
 * {@link ApiException} (which carries a real {@code code()}/{@code message()} - see {@link
 * ProviderErrorCauses#findCause}), and (2) for anything else, {@link
 * ProviderErrorCauses#describeCauseChain} walks every level's message instead of only the outermost
 * wrapper's, so a generic library wrapper never hides what's underneath it again.
 */
@Component
public class ProviderErrorTranslator {

    private static final Logger log = LoggerFactory.getLogger(ProviderErrorTranslator.class);
    static final int DEFAULT_STATUS = 502;
    // Belt-and-braces secret scrubbing for anything that ends up in a message or a
    // log line. Nothing here is expected to carry a key today (LLM keys travel in the
    // Authorization header, not the URL, and error bodies echo the request's model,
    // not its credentials) - but WebClientResponseException#getMessage() embeds the
    // full request URI, and some vendor APIs do accept "?key=" query auth, so a
    // provider/base-URL an admin configures by hand could put one there. See the
    // class javadoc's "never echo the raw request" posture in GlobalExceptionHandler.
    private static final Pattern SECRET_QUERY_PARAM_PATTERN =
            Pattern.compile(
                    "(?i)([?&](?:key|api[-_]?key|access[-_]?token|token|password)=)[^&\\s\"']+");
    private static final Pattern BEARER_TOKEN_PATTERN =
            Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._\\-]{8,}");

    private final ObjectMapper objectMapper;
    private final ErrorExplanationService errorExplanationService;
    private final com.agenticai.core.providers.ProviderModelCatalogService
            providerModelCatalogService;

    /**
     * Pre-catalog-enrichment shape, kept so existing direct constructions (tests) compile unchanged
     * - no catalog means no enrichment, never an error.
     */
    public ProviderErrorTranslator(
            ObjectMapper objectMapper, ErrorExplanationService errorExplanationService) {
        this(objectMapper, errorExplanationService, null);
    }

    /**
     * @param errorExplanationService the Smart AI error explainer used by the generic
     *     unstructured-exception branch only (see {@link ErrorExplanationService} - budget-gated
     *     via {@code PlatformFeatureBudgetService}, feature key {@code "error-explanation"}).
     *     Null-tolerated (tests construct this translator directly without Spring): null simply
     *     means every fallback keeps the static {@link #classify} message, same as a blocked
     *     budget.
     * @param providerModelCatalogService the same daily-refreshed model catalog backing the BYOK
     *     model dropdown - used ONLY to enrich the invalid-model consumer message with what models
     *     the provider actually serves today (see {@link #enrichedInvalidModelMessage}). Legal
     *     dependency direction: core/chat already compiles against core/providers (see {@code
     *     ErrorExplanationService} and this class's own {@code ProviderName} parameter).
     *     Null-tolerated for the same reason as the explainer: null means the static invalid-model
     *     message, never an error.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ProviderErrorTranslator(
            ObjectMapper objectMapper,
            ErrorExplanationService errorExplanationService,
            com.agenticai.core.providers.ProviderModelCatalogService providerModelCatalogService) {
        this.objectMapper = objectMapper;
        this.errorExplanationService = errorExplanationService;
        this.providerModelCatalogService = providerModelCatalogService;
    }

    /**
     * Convenience overload for call sites that don't know which provider is calling (see {@link
     * #translate(Throwable, com.agenticai.core.providers.ProviderName)}).
     */
    public UpstreamProviderException translate(Throwable error) {
        return translate(error, null);
    }

    /**
     * @param provider the provider being called when {@code error} occurred, if known - folded into
     *     the technical log message only (never the consumer message) so server-side logs/the admin
     *     LLM Calls log can tell which provider failed without re-deriving it from context.
     */
    public UpstreamProviderException translate(
            Throwable error, com.agenticai.core.providers.ProviderName provider) {
        // Idempotence guard: an already-translated failure passes through
        // untouched. Without this, a second translate() pass (e.g. an error a
        // fallback gate classified inside AgentChatService#callWithProviderFallback
        // and re-threw, then the caller's own onErrorMap translating again) would
        // fall through to the unstructured branch and MANGLE it - collapsing the
        // real upstream status to the generic 502 and re-wrapping the clean
        // message. Translation is a boundary operation; crossing it twice must
        // be a no-op.
        if (error instanceof UpstreamProviderException alreadyTranslated) {
            return alreadyTranslated;
        }
        String providerTag = provider == null ? "" : "[" + provider + "] ";

        WebClientResponseException wcre =
                ProviderErrorCauses.findCause(error, WebClientResponseException.class);
        if (wcre != null) {
            int upstreamStatus = wcre.getStatusCode().value();
            String body = wcre.getResponseBodyAsString();
            // An empty error body used to mean the message degraded to just
            // "404 Not Found from POST <uri>" - true, but useless for telling a
            // misconfigured model apart from a wrong base URL. Keeping BOTH the
            // request line and whatever body there is (rather than one or the
            // other) is what makes a hosted-pool 404 diagnosable from the log
            // alone: the URI names the endpoint, the body names the model.
            String message =
                    (body == null || body.isBlank())
                            ? wcre.getMessage()
                            : wcre.getMessage() + ": " + extractMessage(body).orElse(body);
            message = redactSecrets(message);
            log.warn(
                    "{}LLM provider call failed (HTTP {}): {}",
                    providerTag,
                    upstreamStatus,
                    message,
                    error);
            return new UpstreamProviderException(
                    upstreamStatus,
                    providerTag
                            + "The LLM provider rejected the request (HTTP "
                            + upstreamStatus
                            + "): "
                            + message,
                    classify(upstreamStatus, message, provider),
                    providerNameOf(provider));
        }

        // Google GenAI SDK (Gemini) - com.google.genai.errors.ApiException (and its
        // ClientException/ServerException subclasses) carries the real HTTP status
        // (code()) and provider error message (message()) even though Spring AI's
        // GoogleGenAiChatModel wraps it in a generic "Failed to generate content"
        // RuntimeException before it ever reaches this translator - see class javadoc.
        ApiException genaiError = ProviderErrorCauses.findCause(error, ApiException.class);
        if (genaiError != null) {
            int upstreamStatus = genaiError.code();
            String message =
                    redactSecrets(
                            firstNonBlank(
                                    genaiError.message(),
                                    genaiError.getMessage(),
                                    "Unknown provider error"));
            log.warn(
                    "{}LLM provider call failed (HTTP {}): {}",
                    providerTag,
                    upstreamStatus,
                    message,
                    error);
            return new UpstreamProviderException(
                    upstreamStatus,
                    providerTag
                            + "The LLM provider rejected the request (HTTP "
                            + upstreamStatus
                            + "): "
                            + message,
                    classify(upstreamStatus, message, provider),
                    providerNameOf(provider));
        }

        // Spring AI's DefaultToolCallingManager throws a plain IllegalStateException
        // ("No ToolCallback found for tool name: X") when the model emits a tool call
        // for a tool that isn't in the ToolCallback set resolved for THIS turn - live
        // symptom: a tenant's dynamic MCP connector (e.g. an STDIO server) died or was
        // never reconnected, an earlier turn's history still references a tool it used
        // to expose, and the model tries to call it again. This has nothing to do with
        // the LLM provider - every provider in the hosted pool would fail on it
        // identically, since none of them control the tool list - so it was previously
        // falling into the generic 502 branch below and telling the tenant "the AI
        // provider is temporarily unavailable," which sent them looking in the wrong
        // place entirely. Classified separately so the message actually points at the
        // agent's Tools/Connectors instead.
        String missingToolName = ProviderErrorCauses.findMissingToolCallbackName(error);
        if (missingToolName != null) {
            String message =
                    providerTag
                            + "A tool call referenced '"
                            + missingToolName
                            + "', which has no live ToolCallback for this turn (its connector is likely disconnected)";
            log.warn("{}{}", providerTag, message, error);
            return new UpstreamProviderException(
                    422,
                    message,
                    "This agent tried to use a tool ('"
                            + missingToolName
                            + "') that is currently unavailable - "
                            + "its connector may be disconnected. Check this agent's Tools/Connectors, then try again.",
                    providerNameOf(provider));
        }

        // Timeout, anywhere in the chain - a distinct, actionable case ("timeout
        // information" per direct feedback) rather than folding into the generic
        // 502 default, which reads like a provider outage rather than "this
        // particular call took too long."
        Throwable timeoutCause = ProviderErrorCauses.findTimeoutCause(error);
        if (timeoutCause != null) {
            String detail = redactSecrets(ProviderErrorCauses.describeCauseChain(error));
            log.warn("{}LLM provider call timed out: {}", providerTag, detail, error);
            return new UpstreamProviderException(
                    408,
                    providerTag + "The LLM provider call timed out: " + detail,
                    "The AI provider took too long to respond. Please try again.",
                    providerNameOf(provider));
        }

        // Both message-shaped branches (AnthropicApi's "Status: [...], Body:[...]"
        // and Spring AI's own "<status> - <body>" retry exceptions) are now looked
        // for at EVERY level of the cause chain, not only on the outermost
        // exception - Spring AI wraps provider errors in a generic
        // RuntimeException often enough (see the class javadoc's "Failed to
        // generate content" case) that checking only error.getMessage() loses the
        // real status routinely.
        ProviderErrorCauses.StatusAndBody structured =
                ProviderErrorCauses.findStructuredStatus(error);
        if (structured != null) {
            int upstreamStatus = structured.status();
            String body = structured.body();
            String message = redactSecrets(extractMessage(body).orElse(body));
            log.warn(
                    "{}LLM provider call failed (HTTP {}): {}",
                    providerTag,
                    upstreamStatus,
                    message,
                    error);
            return new UpstreamProviderException(
                    upstreamStatus,
                    providerTag
                            + "The LLM provider rejected the request (HTTP "
                            + upstreamStatus
                            + "): "
                            + message,
                    classify(upstreamStatus, message, provider),
                    providerNameOf(provider));
        }

        // Direct feedback ("I want to display the proper meaningful messages
        // to consumer, not raw http responses...even if there is an issue
        // from llm response") - no structured [Status]/[Body] shape and no
        // known typed cause anywhere in the chain. describeCauseChain walks
        // every level's own message (see its javadoc) instead of using only
        // error.getMessage(), so a generic library wrapper - Spring AI's
        // GoogleGenAiChatModel's "Failed to generate content" included -
        // never hides the real cause underneath it the way the single-level
        // error.getMessage() used to.
        String detail = redactSecrets(ProviderErrorCauses.describeCauseChain(error));
        log.warn(
                "{}LLM provider call failed with an unstructured exception: {}",
                providerTag,
                detail,
                error);
        return new UpstreamProviderException(
                DEFAULT_STATUS,
                providerTag + "The LLM provider call failed: " + detail,
                consumerMessageForUnstructured(detail, provider),
                providerNameOf(provider));
    }

    /**
     * Null-safe {@code ProviderName -> String} for {@link UpstreamProviderException}'s {@code
     * sourceId}-shaped field - see its 4-arg constructor's javadoc.
     */
    private static String providerNameOf(com.agenticai.core.providers.ProviderName provider) {
        return provider == null ? null : provider.name();
    }

    /**
     * Consumer message for the generic unstructured branch - the one branch where {@link #classify}
     * has the least to work with, so it is the first (and currently only) call site of the Smart AI
     * error explainer: a budget-gated, cached LLM rewrite of {@code detail} into plain language
     * (see {@link ErrorExplanationService}). Any empty/absent/failed explanation degrades to the
     * exact static {@link #classify} text this branch always produced - a tenant never sees an
     * error about the explainer itself.
     */
    private String consumerMessageForUnstructured(
            String detail, com.agenticai.core.providers.ProviderName provider) {
        if (errorExplanationService != null) {
            try {
                Optional<String> explanation =
                        errorExplanationService.explain(DEFAULT_STATUS, detail);
                if (explanation.isPresent()) {
                    return explanation.get();
                }
            } catch (RuntimeException e) {
                // explain() is contractually non-throwing - this is belt-and-braces,
                // matching this translator's own "never throws while translating" rule.
                log.debug("Error-explanation lookup failed - using static classification", e);
            }
        }
        return classify(DEFAULT_STATUS, detail, provider);
    }

    /**
     * Strips anything credential-shaped out of a message before it is logged or returned - see
     * {@link #SECRET_QUERY_PARAM_PATTERN}'s comment for why this is defence-in-depth rather than a
     * fix for a known leak.
     */
    static String redactSecrets(String text) {
        if (text == null) {
            return null;
        }
        String redacted = SECRET_QUERY_PARAM_PATTERN.matcher(text).replaceAll("$1***");
        return BEARER_TOKEN_PATTERN.matcher(redacted).replaceAll("$1***");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Turns (upstream HTTP status, raw provider/exception text) into a short, plain-language
     * sentence with zero HTTP status codes, provider error codes, or raw JSON in it - the whole
     * point being that a tenant reading this never needs to interpret an HTTP status or a
     * third-party error shape to know what to do next. Keyword sniffing on {@code detail} is
     * deliberately narrow (a handful of well-known phrases every major provider -
     * OpenAI/Anthropic/Gemini/OpenRouter/Grok/Sarvam - actually uses) so an unrecognized detail
     * always falls through to the safe, still-accurate default for its status rather than guessing.
     */
    private String classify(
            int upstreamStatus, String detail, com.agenticai.core.providers.ProviderName provider) {
        String lower = detail == null ? "" : detail.toLowerCase(Locale.ROOT);

        if (lower.contains("context length")
                || lower.contains("context_length")
                || lower.contains("maximum context")) {
            return "That request is too long for the selected model. Try shortening the message or attaching less content.";
        }
        if (lower.contains("content_policy")
                || lower.contains("content management policy")
                || lower.contains("content filter")) {
            return "The AI provider declined to respond because the request tripped its content policy. Try rephrasing it.";
        }
        if (isInvalidModelMessage(lower)) {
            String enriched = enrichedInvalidModelMessage(detail, provider);
            if (enriched != null) {
                return enriched;
            }
            return "The selected AI model isn't available from this provider anymore. Choose a different model for this agent.";
        }
        if (lower.contains("insufficient")
                || lower.contains("quota")
                || lower.contains("billing")
                || lower.contains("credit")) {
            return "The AI provider account has run out of credits or hit its billing limit. Check billing with that provider.";
        }
        if (lower.contains("rate limit") || lower.contains("rate_limit") || upstreamStatus == 429) {
            return "The AI provider is receiving too many requests right now. Please wait a moment and try again.";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || upstreamStatus == 408) {
            return "The AI provider took too long to respond. Please try again.";
        }
        if (lower.contains("unauthorized")
                || lower.contains("invalid api key")
                || lower.contains("invalid_api_key")
                || lower.contains("authentication")) {
            return "The AI provider rejected our credentials. Check the API key for this provider in Providers settings.";
        }

        return switch (upstreamStatus) {
            case 400 ->
                    "The AI provider couldn't process this request - the model or input may not be supported. Try a different model or simplify the request.";
            case 401, 403 ->
                    "The AI provider rejected our credentials. Check the API key for this provider in Providers settings.";
            case 404 ->
                    "The selected AI model isn't available from this provider anymore. Choose a different model for this agent.";
            case 408 -> "The AI provider took too long to respond. Please try again.";
            case 413 ->
                    "That request is too large for the selected model. Try shortening the message or attachment.";
            case 422 -> "The AI provider couldn't process this request as sent. Try rephrasing it.";
            case 500, 502, 503, 504, 529 ->
                    "The AI provider is temporarily unavailable. Please try again in a moment.";
            default ->
                    "The AI provider call failed. Please try again, and contact support if this keeps happening.";
        };
    }

    /**
     * The ONE home of the "this model id doesn't exist / was retired" phrase family - exactly the
     * phrases {@link #classify}'s invalid-model branch has always sniffed for (OpenAI's "The model
     * `x` does not exist", Google's "models/x is not found for API version ...", Groq's "model ...
     * has been decommissioned"), now callable by name so other modules never grow a second,
     * drifting copy. Current external caller: {@code core/agents}' hosted-pool invalid-model
     * self-heal ({@code AgentChatService}), which re-runs model auto-selection for a pool row whose
     * stored model id the upstream rejects with one of these.
     *
     * <p>Deliberately narrow, same posture as the rest of {@link #classify}'s keyword sniffing: an
     * unrecognized phrasing simply isn't classified as an invalid model, rather than being guessed
     * at.
     */
    public static boolean isInvalidModelMessage(String detail) {
        if (detail == null) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("model")
                && (lower.contains("does not exist")
                        || lower.contains("not found")
                        || lower.contains("not_found")
                        || lower.contains("decommission"));
    }

    // The two shapes a provider's own invalid-model text actually names the model
    // id in: a quoted/backticked token (OpenAI/OpenRouter/Groq: "The model
    // `gpt-x` does not exist or you do not have access to it") and Google's bare
    // "models/gemini-x is not found for API version v1beta" path form.
    private static final Pattern QUOTED_MODEL_ID_PATTERN =
            Pattern.compile("[`'\"]([A-Za-z0-9][A-Za-z0-9._/:-]{1,199})[`'\"]");
    private static final Pattern MODELS_PATH_MODEL_ID_PATTERN =
            Pattern.compile("\\bmodels/([A-Za-z0-9][A-Za-z0-9._-]{1,199})");

    /**
     * The model id named inside an invalid-model error body, or null when none is recognizably
     * present.
     */
    static String extractModelId(String detail) {
        if (detail == null) {
            return null;
        }
        Matcher quoted = QUOTED_MODEL_ID_PATTERN.matcher(detail);
        if (quoted.find()) {
            return quoted.group(1);
        }
        Matcher path = MODELS_PATH_MODEL_ID_PATTERN.matcher(detail);
        if (path.find()) {
            return path.group(1);
        }
        return null;
    }

    /**
     * Actionable upgrade of the static invalid-model consumer message: when the failed body names
     * its model id and the provider's cached catalog (the same one the BYOK model dropdown reads)
     * confirms that id is NOT currently served, tell the tenant so - including a known retirement
     * replacement if the catalog has one, and up to three models the provider does serve - so
     * "choose a different model" comes with an actual answer to "which one?".
     *
     * <p>Returns {@code null} - meaning "keep the static message" - whenever anything is missing or
     * goes wrong: no injected catalog (tests, or the 2-arg constructor), no provider identity, no
     * extractable model id, the catalog still listing the model (then the static text is the honest
     * one), an empty catalog, or ANY exception from the catalog lookup. A catalog problem must
     * never break error translation itself.
     */
    private String enrichedInvalidModelMessage(
            String detail, com.agenticai.core.providers.ProviderName provider) {
        if (providerModelCatalogService == null || provider == null) {
            return null;
        }
        try {
            String modelId = extractModelId(detail);
            if (modelId == null) {
                return null;
            }
            if (providerModelCatalogService.findModel(provider, modelId).isPresent()) {
                // The catalog still lists it - so "not in the current model
                // list" would be false. The static message stands.
                return null;
            }
            StringBuilder message =
                    new StringBuilder(
                            "Model '"
                                    + modelId
                                    + "' is not in "
                                    + provider
                                    + "'s current model list - it may have been retired.");
            String replacement =
                    providerModelCatalogService.replacementForRetiredModel(modelId).orElse(null);
            if (replacement != null) {
                message.append(" Its suggested replacement is '").append(replacement).append("'.");
            }
            List<String> available =
                    providerModelCatalogService.listModels(provider).stream()
                            .map(com.agenticai.core.providers.ProviderModelResponse::modelId)
                            .limit(3)
                            .toList();
            if (!available.isEmpty()) {
                message.append(" Available models include: ")
                        .append(String.join(", ", available))
                        .append(".");
            }
            return message.toString();
        } catch (RuntimeException catalogFailure) {
            // Same "never throws while translating" rule as everything else here.
            log.debug(
                    "Could not enrich the invalid-model message from the provider model catalog",
                    catalogFailure);
            return null;
        }
    }

    private Optional<String> extractMessage(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.path("error").has("message")) {
                return Optional.of(root.path("error").path("message").asString());
            }
            if (root.has("message")) {
                return Optional.of(root.path("message").asString());
            }
        } catch (Exception e) {
            log.debug("Could not parse upstream error body as JSON, using raw body", e);
        }
        return Optional.empty();
    }
}
