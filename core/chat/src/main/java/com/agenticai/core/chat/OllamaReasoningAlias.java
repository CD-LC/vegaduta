package com.agenticai.core.chat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.BodyExtractors;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Ollama's OpenAI-compatible API carries a model's thinking text in a {@code reasoning} field on
 * each streamed delta and on the final message. Spring AI's {@code ChatCompletionMessage} binds
 * {@code reasoning_content} (the DeepSeek convention) instead, so the field was silently dropped
 * and reasoning never reached the turn's metadata. Rewriting the wire JSON field name before
 * Spring AI deserializes it surfaces thinking in {@code Generation} metadata, where {@code
 * ReasoningEmitAdvisor} picks it up as {@code thinking} step events.
 *
 * <p>The replacement only fires on an UNESCAPED {@code "reasoning":} key: the same characters
 * inside generated text are JSON-escaped ({@code \"reasoning\":}), so content is never touched.
 * Attached only to the Ollama client's transports - other providers are byte-identical.
 */
public final class OllamaReasoningAlias {

    private static final Pattern REASONING_FIELD = Pattern.compile("\"reasoning\"\\s*:");
    private static final String ALIAS = "\"reasoning_content\":";

    private OllamaReasoningAlias() {}

    /** Field-name rewrite for a JSON document or a single SSE data line. */
    static String alias(String json) {
        return REASONING_FIELD.matcher(json).replaceAll(ALIAS);
    }

    /**
     * WebClient filter for the streamed path. SSE bodies are transformed per {@code data:} line
     * (a line can split across buffers, so bytes are carried until a newline). Non-SSE bodies
     * pass through untouched.
     */
    public static ExchangeFilterFunction streamFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(
                response -> {
                    var contentType = response.headers().contentType().orElse(null);
                    if (contentType == null
                            || !"text/event-stream".equalsIgnoreCase(contentType.getType() + "/" + contentType.getSubtype())) {
                        return Mono.just(response);
                    }
                    Flux<DataBuffer> rewritten =
                            aliasBuffers(response.body(BodyExtractors.toDataBuffers()));
                    // A fresh response, not response.mutate(): a mutated response keeps a
                    // handle on the original connection body, and reading both fails with
                    // "The client response body can only be consumed once" (every streamed
                    // reply failed on 2026-10-18; see OllamaReasoningAliasWireTest).
                    return Mono.just(
                            ClientResponse.create(
                                            response.statusCode(),
                                            org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults())
                                    .headers(h -> h.addAll(response.headers().asHttpHeaders()))
                                    .cookies(c -> c.addAll(response.cookies()))
                                    .body(rewritten)
                                    .build());
                });
    }

    /** RestClient interceptor for the blocking path - the whole body is rewritten at once. */
    public static ClientHttpRequestInterceptor restInterceptor() {
        return (request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            byte[] original = response.getBody().readAllBytes();
            byte[] rewritten = alias(new String(original, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            return new BodyReplacedClientHttpResponse(response, rewritten);
        };
    }

    private static Flux<DataBuffer> aliasBuffers(Flux<DataBuffer> body) {
        DataBufferFactory factory = org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance;
        List<byte[]> carry = new ArrayList<>(1);
        carry.add(new byte[0]);
        return body.concatMap(
                        buffer -> {
                            byte[] incoming = new byte[buffer.readableByteCount()];
                            buffer.read(incoming);
                            DataBufferUtils.release(buffer);
                            byte[] joined = concat(carry.get(0), incoming);
                            int cut = lastNewline(joined);
                            if (cut < 0) {
                                carry.set(0, joined);
                                return Flux.empty();
                            }
                            byte[] head = java.util.Arrays.copyOf(joined, cut + 1);
                            carry.set(0, java.util.Arrays.copyOfRange(joined, cut + 1, joined.length));
                            return Flux.just(factory.wrap(aliasLines(head)));
                        })
                .concatWith(
                        Flux.defer(
                                () -> {
                                    byte[] tail = carry.get(0);
                                    return tail.length == 0
                                            ? Flux.empty()
                                            : Flux.just(factory.wrap(aliasLines(tail)));
                                }));
    }

    /** Alias only inside {@code data:} payload lines; comments, ids and events pass verbatim. */
    private static byte[] aliasLines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(text.length());
        int pos = 0;
        while (pos < text.length()) {
            int nl = text.indexOf('\n', pos);
            int end = nl < 0 ? text.length() : nl + 1;
            String line = text.substring(pos, end);
            out.append(line.startsWith("data:") ? alias(line) : line);
            pos = end;
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static int lastNewline(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        if (a.length == 0) {
            return b;
        }
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Minimal ClientHttpResponse delegate whose body is the rewritten JSON. */
    private static final class BodyReplacedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        private BodyReplacedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode() throws java.io.IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws java.io.IOException {
            return delegate.getStatusText();
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public java.io.InputStream getBody() {
            return new java.io.ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
