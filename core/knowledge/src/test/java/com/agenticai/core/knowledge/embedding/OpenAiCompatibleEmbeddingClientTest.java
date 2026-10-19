package com.agenticai.core.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.knowledge.SidecarCommunicationException;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleEmbeddingClientTest {

    private static EmbeddingProperties props(int dims) {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setProvider("openai-compatible");
        p.setBaseUrl("http://embed.test/v1/");
        p.setModel("all-minilm");
        p.setDimensions(dims);
        return p;
    }

    @Test
    void postsOpenAiShapedRequestAndReordersByIndex() {
        EmbeddingProperties p = props(2);
        p.setApiKey("k-123");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://embed.test/v1/embeddings"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer k-123"))
            .andExpect(jsonPath("$.model").value("all-minilm"))
            .andExpect(jsonPath("$.input[1]").value("b"))
            .andExpect(jsonPath("$.dimensions").doesNotExist())
            .andRespond(withSuccess("{\"data\":[{\"index\":1,\"embedding\":[0,2]},{\"index\":0,\"embedding\":[3,4]}]}",
                MediaType.APPLICATION_JSON));
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(p, builder);

        List<float[]> out = client.embed(List.of("a", "b"));

        assertThat(out.get(0)).containsExactly(0.6f, 0.8f); // normalized [3,4]
        assertThat(out.get(1)).containsExactly(0f, 1f);
        server.verify();
    }

    @Test
    void batchesAndSendsDimensionsWhenEnabled() {
        EmbeddingProperties p = props(1);
        p.setBatchSize(2);
        p.setSendDimensions(true);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://embed.test/v1/embeddings"))
            .andExpect(headerDoesNotExist("Authorization"))
            .andExpect(jsonPath("$.dimensions").value(1))
            .andExpect(jsonPath("$.input", Matchers.hasSize(2)))
            .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1]},{\"index\":1,\"embedding\":[1]}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://embed.test/v1/embeddings"))
            .andExpect(jsonPath("$.input", Matchers.hasSize(1)))
            .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1]}]}", MediaType.APPLICATION_JSON));
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(p, builder);

        assertThat(client.embed(List.of("a", "b", "c"))).hasSize(3);
        server.verify();
    }

    @Test
    void dimensionMismatchInResponseIsAClearError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://embed.test/v1/embeddings"))
            .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}", MediaType.APPLICATION_JSON));
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(props(2), builder);

        assertThatThrownBy(() -> client.embed(List.of("a")))
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("3-dim vector but app.embeddings.dimensions=2");
    }

    @Test
    void serverErrorsSurfaceAsSidecarCommunicationException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://embed.test/v1/embeddings")).andRespond(withServerError());
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(props(2), builder);

        assertThatThrownBy(() -> client.embed(List.of("a")))
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("HTTP 500");
    }

    @Test
    void failsFastOnMissingBaseUrlModelOrOversizedDimensions() {
        EmbeddingProperties p = new EmbeddingProperties();
        p.setDimensions(1536);
        assertThatThrownBy(() -> OpenAiCompatibleEmbeddingClient.validate(p))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.embeddings.base-url")
            .hasMessageContaining("app.embeddings.model")
            .hasMessageContaining("app.embeddings.dimensions (APP_EMBEDDINGS_DIMENSIONS) is 1536");
    }

    @Test
    void acceptsDimensionsUpToMemoryColumnWidth() {
        OpenAiCompatibleEmbeddingClient.validate(props(EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS));
        assertThatThrownBy(() -> OpenAiCompatibleEmbeddingClient.validate(
                props(EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS + 1)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> OpenAiCompatibleEmbeddingClient.validate(props(0)))
            .isInstanceOf(IllegalStateException.class);
    }
}
