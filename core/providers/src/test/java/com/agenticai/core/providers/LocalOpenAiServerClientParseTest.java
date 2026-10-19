package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.net.SsrfGuard;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** What {@link LocalOpenAiServerClient} reads out of the {@code /v1/models} bodies real servers send. */
class LocalOpenAiServerClientParseTest {

    private final LocalOpenAiServerClient client =
        new LocalOpenAiServerClient(RestClient.builder(), SsrfGuard.of(Set.of(), host -> new java.net.InetAddress[0]));

    private LocalOpenAiServerClient.Listing parse(String json) {
        return client.parse(200, json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void vllmReportsTheContextWindowAsMaxModelLen() {
        var listing = parse("{\"object\":\"list\",\"data\":[{\"id\":\"Qwen/Qwen3-8B\",\"object\":\"model\","
            + "\"max_model_len\":40960,\"owned_by\":\"vllm\"}]}");

        assertThat(listing.ok()).isTrue();
        assertThat(listing.models()).containsExactly(new LocalOpenAiServerClient.ServerModel("Qwen/Qwen3-8B", 40960));
    }

    @Test
    void llamaCppReportsTheTrainedContextUnderMeta() {
        var listing = parse("{\"models\":[{\"model\":\"qwen3-8b.gguf\"}],\"object\":\"list\",\"data\":[{\"id\":\"qwen3-8b.gguf\","
            + "\"meta\":{\"n_ctx_train\":32768,\"n_params\":8190735360}}]}");

        assertThat(listing.models()).containsExactly(new LocalOpenAiServerClient.ServerModel("qwen3-8b.gguf", 32768));
    }

    @Test
    void lmStudioAndLocalAiListPlainIds() {
        var listing = parse("{\"object\":\"list\",\"data\":[{\"id\":\"llama-3.2-3b-instruct\"},{\"id\":\"text-embedding-nomic\"}]}");

        assertThat(listing.models()).extracting(LocalOpenAiServerClient.ServerModel::id)
            .containsExactly("llama-3.2-3b-instruct", "text-embedding-nomic");
        assertThat(listing.models()).allMatch(m -> m.contextWindow() == null);
    }

    @Test
    void modelsOnlyShapeIsAccepted() {
        var listing = parse("{\"models\":[{\"name\":\"gemma3:4b\"}]}");

        assertThat(listing.ok()).isTrue();
        assertThat(listing.models()).extracting(LocalOpenAiServerClient.ServerModel::id).containsExactly("gemma3:4b");
    }

    @Test
    void duplicatesAndNamelessEntriesAreDropped() {
        var listing = parse("{\"data\":[{\"id\":\"a\"},{\"id\":\"a\"},{\"object\":\"model\"},{\"id\":\"\"},{\"id\":\"b\"}]}");

        assertThat(listing.models()).extracting(LocalOpenAiServerClient.ServerModel::id).containsExactly("a", "b");
    }

    @Test
    void implausibleContextValuesAreIgnored() {
        var listing = parse("{\"data\":[{\"id\":\"a\",\"max_model_len\":-1},{\"id\":\"b\",\"max_model_len\":\"big\"},"
            + "{\"id\":\"c\",\"max_model_len\":99999999999}]}");

        assertThat(listing.models()).allMatch(m -> m.contextWindow() == null);
    }

    @Test
    void htmlJsonWithoutAListAndEmptyBodiesAreNotModelLists() {
        assertThat(parse("<html></html>").wellFormed()).isFalse();
        assertThat(parse("{\"status\":\"ok\"}").wellFormed()).isFalse();
        assertThat(parse("[]").wellFormed()).isFalse();
        assertThat(client.parse(200, new byte[0]).wellFormed()).isFalse();
        assertThat(parse("{\"data\":\"nope\"}").wellFormed()).isFalse();
    }

    @Test
    void listingIsOkOnlyFor2xxWithAList() {
        assertThat(new LocalOpenAiServerClient.Listing(200, true, java.util.List.of()).ok()).isTrue();
        assertThat(new LocalOpenAiServerClient.Listing(200, false, java.util.List.of()).ok()).isFalse();
        assertThat(new LocalOpenAiServerClient.Listing(500, true, java.util.List.of()).ok()).isFalse();
    }
}
