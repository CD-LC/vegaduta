package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Ollama answers a request with HTTP 500 when the model wrote a malformed tool call; the same request
 * usually succeeds the second time. Only that error is replayed, and only once.
 */
class OllamaToolCallParseRetryTest {

    private static final String PARSE_ERROR =
            "{\"error\":{\"message\":\"XML syntax error on line 3: element <function> closed by </parameter>\",\"type\":\"api_error\"}}";

    private final ClientRequest request =
            ClientRequest.create(HttpMethod.POST, URI.create("http://ollama:11434/v1/chat/completions")).build();

    private static ClientResponse reply(HttpStatus status, String body) {
        return ClientResponse.create(status).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build();
    }

    private ClientResponse run(ExchangeFunction upstream) {
        return OllamaToolCallParseRetry.filter().filter(request, upstream).block();
    }

    @Test
    void aMalformedToolCallIsReplayedOnceAndTheSecondAnswerWins() {
        AtomicInteger calls = new AtomicInteger();
        ClientResponse out =
                run(req -> Mono.just(calls.incrementAndGet() == 1 ? reply(HttpStatus.INTERNAL_SERVER_ERROR, PARSE_ERROR) : reply(HttpStatus.OK, "{\"ok\":true}")));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(out.statusCode().value()).isEqualTo(200);
        assertThat(out.bodyToMono(String.class).block()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void theSecondFailureIsReportedAsItCameWithoutAThirdTry() {
        AtomicInteger calls = new AtomicInteger();
        ClientResponse out =
                run(
                        req -> {
                            calls.incrementAndGet();
                            return Mono.just(reply(HttpStatus.INTERNAL_SERVER_ERROR, PARSE_ERROR));
                        });

        assertThat(calls.get()).isEqualTo(2);
        assertThat(out.statusCode().value()).isEqualTo(500);
    }

    @Test
    void anyOther500PassesThroughWithItsBodyIntact() {
        AtomicInteger calls = new AtomicInteger();
        ClientResponse out =
                run(
                        req -> {
                            calls.incrementAndGet();
                            return Mono.just(reply(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"model runner crashed\"}"));
                        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(out.statusCode().value()).isEqualTo(500);
        assertThat(out.bodyToMono(String.class).block()).contains("model runner crashed");
    }

    @Test
    void successAndOtherStatusesAreUntouched() {
        AtomicInteger calls = new AtomicInteger();
        ClientResponse ok = run(req -> Mono.just(reply(HttpStatus.OK, "fine")));
        ClientResponse bad = run(req -> {
            calls.incrementAndGet();
            return Mono.just(reply(HttpStatus.BAD_REQUEST, PARSE_ERROR));
        });

        assertThat(ok.statusCode().value()).isEqualTo(200);
        assertThat(bad.statusCode().value()).isEqualTo(400);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void bothOllamaWordingsAreRecognised() {
        assertThat(OllamaToolCallParseRetry.isMalformedToolCall(PARSE_ERROR)).isTrue();
        assertThat(OllamaToolCallParseRetry.isMalformedToolCall("{\"error\":{\"message\":\"expected element type <function> but have <parameter>\"}}")).isTrue();
        assertThat(OllamaToolCallParseRetry.isMalformedToolCall("{\"error\":\"out of memory\"}")).isFalse();
        assertThat(OllamaToolCallParseRetry.isMalformedToolCall(null)).isFalse();
    }
}
