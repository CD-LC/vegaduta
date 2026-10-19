package com.agenticai.core.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.knowledge.SidecarCommunicationException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaEmbeddingClientTest {

    private static final String BASE = "http://ollama.test";

    private static EmbeddingProperties props(String model) {
        EmbeddingProperties p = new EmbeddingProperties();
        p.getOllama().setModel(model);
        return p;
    }

    private static String vectorJson(int width, double first) {
        StringBuilder sb = new StringBuilder("[").append(first);
        for (int i = 1; i < width; i++) {
            sb.append(",0");
        }
        return sb.append(']').toString();
    }

    @Test
    void postsNativeEmbedRequestWithDocumentPrefixAndNormalizes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.model").value("nomic-embed-text"))
            .andExpect(jsonPath("$.input[0]").value("search_document: hello"))
            .andExpect(jsonPath("$.truncate").value(true))
            .andExpect(jsonPath("$.keep_alive").value("10m"))
            .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(768, 3) + "]}", MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("nomic-embed-text"), BASE, builder);

        List<float[]> out = client.embed(List.of("hello"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0)).hasSize(768);
        assertThat(out.get(0)[0]).isEqualTo(1f); // normalized
        assertThat(client.dimensions()).isEqualTo(768);
        assertThat(client.modelId()).isEqualTo("nomic-embed-text");
        assertThat(client.supportsTruncation()).isTrue();
        server.verify();
    }

    @Test
    void queriesUseTheQueryPrefix() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed"))
            .andExpect(jsonPath("$.input[0]").value("search_query: refund policy"))
            .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(768, 1) + "]}", MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("nomic-embed-text:latest"), BASE, builder);

        assertThat(client.embedQuery(List.of("refund policy"))).hasSize(1);
        server.verify();
    }

    @Test
    void explicitEmptyPrefixOverridesTheModelDefault() {
        EmbeddingProperties p = props("nomic-embed-text");
        p.getOllama().setDocumentPrefix("");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed"))
            .andExpect(jsonPath("$.input[0]").value("plain"))
            .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(768, 1) + "]}", MediaType.APPLICATION_JSON));

        new OllamaEmbeddingClient(p, BASE, builder).embed(List.of("plain"));
        server.verify();
    }

    @Test
    void batchesBySize() {
        EmbeddingProperties p = props("all-minilm");
        p.setBatchSize(2);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String two = "{\"embeddings\":[" + vectorJson(384, 1) + "," + vectorJson(384, 1) + "]}";
        String one = "{\"embeddings\":[" + vectorJson(384, 1) + "]}";
        server.expect(requestTo(BASE + "/api/embed")).andRespond(withSuccess(two, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/embed")).andRespond(withSuccess(one, MediaType.APPLICATION_JSON));

        assertThat(new OllamaEmbeddingClient(p, BASE, builder).embed(List.of("a", "b", "c"))).hasSize(3);
        server.verify();
    }

    @Test
    void missingModelNamesTheOllamaPullCommand() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"error\":\"model not found\"}").contentType(MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("all-minilm"), BASE, builder);
        assertThatThrownBy(() -> client.embed(List.of("x")))
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("ollama pull all-minilm");
    }

    @Test
    void aVectorOfTheWrongWidthIsRefusedNeverMixed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed"))
            .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(384, 1) + "]}", MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("nomic-embed-text"), BASE, builder);

        assertThatThrownBy(() -> client.embed(List.of("x")))
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("384-dim vector")
            .hasMessageContaining("768 dims were expected")
            .hasMessageContaining("Refusing to mix widths");
    }

    @Test
    void anUnknownModelLearnsItsWidthThenEnforcesIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed"))
            .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(256, 1) + "]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/embed"))
            .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(128, 1) + "]}", MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("my-custom-embedder"), BASE, builder);

        assertThat(client.embed(List.of("a")).get(0)).hasSize(256);
        assertThat(client.dimensions()).isEqualTo(256);
        assertThatThrownBy(() -> client.embed(List.of("b"))).hasMessageContaining("Refusing to mix widths");
    }

    @Test
    void anUnknownModelWiderThanTheColumnIsRefusedAndNotRemembered() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo(BASE + "/api/embed"))
                .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(1536, 1) + "]}", MediaType.APPLICATION_JSON));
        }
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("huge-embedder"), BASE, builder);

        assertThatThrownBy(() -> client.embed(List.of("a"))).hasMessageContaining("wider than the 768-dim");
        // Still refused the second time: the too-wide width was not adopted as "learned".
        assertThatThrownBy(() -> client.embed(List.of("a"))).hasMessageContaining("wider than the 768-dim");
    }

    @Test
    void startupRejectsAWideNonTruncatableModelWithTheFix() {
        assertThatThrownBy(() -> new OllamaEmbeddingClient(props("bge-m3"), BASE, RestClient.builder()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.embeddings.provider=ollama is misconfigured")
            .hasMessageContaining("1024-dim")
            .hasMessageContaining("allow-truncation");
    }

    @Test
    void bgeM3IsAcceptedWhenTruncationIsExplicitlyAllowed() {
        EmbeddingProperties p = props("bge-m3");
        p.getOllama().setAllowTruncation(true);
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(p, BASE, RestClient.builder());
        assertThat(client.supportsTruncation()).isTrue();
        assertThat(client.dimensions()).isEqualTo(1024);
    }

    @Test
    void qwen3EmbeddingFitsViaMatryoshkaTruncation() {
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("qwen3-embedding"), BASE, RestClient.builder());
        assertThat(client.supportsTruncation()).isTrue();
    }

    @Test
    void qwen3EmbeddingWidthIsLearnedBecauseItVariesBySize() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed"))
            .andRespond(withSuccess("{\"embeddings\":[" + vectorJson(4096, 1) + "]}", MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("qwen3-embedding:8b"), BASE, builder);

        assertThat(client.embed(List.of("a")).get(0)).hasSize(4096);
        assertThat(client.dimensions()).isEqualTo(4096);
    }

    @Test
    void snowflakeSizeTagsLearnTheirWidthButTheDefaultTagIsKnown() {
        assertThat(OllamaEmbeddingModels.lookup("snowflake-arctic-embed:22m").orElseThrow().dimensions()).isZero();
        assertThat(OllamaEmbeddingModels.lookup("snowflake-arctic-embed").orElseThrow().dimensions()).isEqualTo(1024);
        assertThat(OllamaEmbeddingModels.lookup("snowflake-arctic-embed:latest").orElseThrow().dimensions()).isEqualTo(1024);
    }

    @Test
    void startupRejectsAMissingBaseUrlAndModel() {
        EmbeddingProperties p = props(" ");
        assertThatThrownBy(() -> new OllamaEmbeddingClient(p, "", RestClient.builder()))
            .hasMessageContaining("app.embeddings.ollama.base-url")
            .hasMessageContaining("app.embeddings.ollama.model");
    }

    @Test
    void aMalformedOrShortResponseIsAnError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(BASE + "/api/embed")).andRespond(withSuccess("{\"embeddings\":[]}", MediaType.APPLICATION_JSON));
        OllamaEmbeddingClient client = new OllamaEmbeddingClient(props("nomic-embed-text"), BASE, builder);

        assertThatThrownBy(() -> client.embed(List.of("x")))
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("0 embeddings for 1 inputs");
    }
}
