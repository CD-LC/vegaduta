package com.agenticai.core.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cause-chain reading for {@link ProviderErrorTranslator}: every helper walks the whole chain (capped at
 * {@link #MAX_CAUSE_DEPTH}, guarded against a self-referential cause), because Spring AI's provider clients wrap
 * the real upstream failure in generic RuntimeExceptions often enough that the outermost message routinely hides
 * the real status and reason. Pure and static; the translator decides what each finding becomes.
 */
final class ProviderErrorCauses {

    static final int MAX_CAUSE_DEPTH = 12;
    private static final Pattern RESPONSE_EXCEPTION_PATTERN =
        Pattern.compile("Status:\\s*\\[(\\d{3})[^]]*],\\s*Body:\\[(.*)]\\s*$", Pattern.DOTALL);
    // Spring AI's own RestClient error handler (RetryUtils#DEFAULT_RESPONSE_ERROR_HANDLER,
    // which OpenAiApi installs via RestClient.Builder#defaultStatusHandler) does NOT
    // throw a typed HTTP exception: for a 4xx it throws NonTransientAiException, for a
    // 5xx TransientAiException, and in both cases the ENTIRE message is literally
    // String.format("%s - %s", status, body). That is the shape every NON-streaming
    // OpenAI-compat turn produces - the hosted pool (HostedLlmChatClientFactory) and
    // the OpenRouter/Grok BYOK factories all go through OpenAiApi. Neither the
    // WebClientResponseException branch (that one is the STREAMING path) nor
    // RESPONSE_EXCEPTION_PATTERN matches it, so before this pattern existed a real
    // upstream 404 "model not found" on that path collapsed into the generic 502
    // default and the provider's own text only survived as an opaque blob. Matched by
    // message shape rather than by exception type deliberately: core/chat has no
    // compile dependency on spring-ai-retry, and the shape is what actually reaches us.
    private static final Pattern AI_RETRY_STATUS_PATTERN =
        Pattern.compile("^(\\d{3})\\s+-\\s+(.*)$", Pattern.DOTALL);

    private ProviderErrorCauses() {
    }

    /**
     * Walks {@code error}'s cause chain (including {@code error} itself) looking for the
     * first {@link Throwable} assignable to {@code type} - e.g. a {@link WebClientResponseException}
     * or {@link ApiException} nested several levels under a generic wrapper RuntimeException a
     * Spring AI provider client threw. Capped at {@link #MAX_CAUSE_DEPTH} and guards against a
     * (theoretically impossible, but not worth trusting) self-referential cause cycle.
     */
    static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        return null;
    }

    /** An upstream status + the raw provider body recovered from a message-shaped exception. */
    record StatusAndBody(int status, String body) {}

    /**
     * Walks the whole cause chain looking for either of the two message shapes a
     * Spring AI provider client is known to encode an upstream status into -
     * {@link #RESPONSE_EXCEPTION_PATTERN} (AnthropicApi) and {@link
     * #AI_RETRY_STATUS_PATTERN} (Spring AI's own NonTransient/TransientAiException,
     * used by every OpenAI-compat NON-streaming call, hosted pool included). Returns
     * {@code null} when neither shape appears anywhere in the chain, which is the
     * caller's signal to fall back to {@link #describeCauseChain} + {@link
     * ProviderErrorTranslator#DEFAULT_STATUS}. The Anthropic shape is tried first at each level because it
     * is the more specific of the two.
     */
    static StatusAndBody findStructuredStatus(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            String message = current.getMessage();
            if (message != null) {
                Matcher responseException = RESPONSE_EXCEPTION_PATTERN.matcher(message);
                if (responseException.find()) {
                    return new StatusAndBody(parseStatus(responseException.group(1)), responseException.group(2));
                }
                Matcher aiRetry = AI_RETRY_STATUS_PATTERN.matcher(message.trim());
                if (aiRetry.matches()) {
                    return new StatusAndBody(parseStatus(aiRetry.group(1)), aiRetry.group(2));
                }
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        return null;
    }

    private static final Pattern MISSING_TOOL_CALLBACK_PATTERN =
        Pattern.compile("No ToolCallback found for tool name:\\s*(\\S+)");

    /**
     * Walks the cause chain for Spring AI's {@code DefaultToolCallingManager}
     * IllegalStateException shape and extracts the tool name it names, or
     * {@code null} if this isn't that failure. See the call site in {@link
     * ProviderErrorTranslator#translate(Throwable, com.agenticai.core.providers.ProviderName)} for why
     * this needs its own classification instead of the generic 502 fallback.
     */
    static String findMissingToolCallbackName(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (current instanceof IllegalStateException && current.getMessage() != null) {
                Matcher matcher = MISSING_TOOL_CALLBACK_PATTERN.matcher(current.getMessage());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        return null;
    }

    /** Same walk as {@link #findCause}, looking for any well-known "this took too long" exception type. */
    static Throwable findTimeoutCause(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            String typeName = current.getClass().getName();
            if (current instanceof java.util.concurrent.TimeoutException
                || current instanceof java.net.SocketTimeoutException
                || typeName.contains("ReadTimeoutException")
                || typeName.contains("ConnectTimeoutException")
                || typeName.contains("TimeoutException")) {
                return current;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        return null;
    }

    /**
     * Builds a readable "OuterType: outer message -&gt; CauseType: cause message -&gt; ..."
     * trail from the full cause chain, deduplicating repeated messages and skipping the class
     * name for bare {@code RuntimeException}/{@code Exception} instances (uninformative noise -
     * e.g. Spring AI's {@code throw new RuntimeException("Failed to generate content", e)} wrapper)
     * so the real, specific cause underneath a generic wrapper is never hidden the way relying on
     * only the outermost {@code error.getMessage()} used to hide it. Falls back to a plain "no
     * further detail available" phrase if nothing in the chain has a message at all - NOT the
     * outer exception's raw simple class name ("RuntimeException", "NullPointerException", etc.),
     * which is exactly the kind of technical leak the rest of this translator exists to strip out
     * (see class javadoc).
     */
    static String describeCauseChain(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        String lastMessage = null;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            String msg = current.getMessage();
            if (msg != null && !msg.isBlank() && !msg.equals(lastMessage)) {
                Class<?> type = current.getClass();
                boolean genericWrapper = type == RuntimeException.class || type == Exception.class;
                if (sb.length() > 0) {
                    sb.append(" -> ");
                }
                if (!genericWrapper) {
                    sb.append(type.getSimpleName()).append(": ");
                }
                sb.append(msg);
                lastMessage = msg;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }
        return sb.length() > 0 ? sb.toString() : "no further detail available";
    }

    private static int parseStatus(String statusGroup) {
        try {
            return Integer.parseInt(statusGroup);
        } catch (NumberFormatException e) {
            return ProviderErrorTranslator.DEFAULT_STATUS;
        }
    }
}
