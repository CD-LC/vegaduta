package com.agenticai.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import com.agenticai.core.knowledge.embedding.EmbeddingClient;
import com.agenticai.core.knowledge.embedding.EmbeddingProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Opt-in external embeddings path of {@link SidecarClient}: no sidecar HTTP
 * (base URL points at a closed port), and the response shape and padding
 * match what the sidecar's /embed returns.
 */
class SidecarClientExternalEmbeddingTest {

    private static final class FakeEmbeddings implements EmbeddingClient {
        final List<List<String>> calls = new ArrayList<>();

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public int dimensions() {
            return 3;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            calls.add(texts);
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new float[] {1f, 0f, 0f});
            }
            return out;
        }
    }

    private final FakeEmbeddings fake = new FakeEmbeddings();
    private final SidecarClient client = new SidecarClient("http://127.0.0.1:1", "", fake);

    @Test
    void queryWithoutModelPadsToMemoryColumnWidth() {
        float[] v = client.embedQuery("hello world");
        assertThat(v).hasSize(EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS);
        assertThat(v[0]).isEqualTo(1f);
        assertThat(v[3]).isEqualTo(0f);
        assertThat(client.embeddingProvider()).isEqualTo("fake");
    }

    @Test
    void queryWithCollectionModelPadsToKnowledgeColumnWidth() {
        float[] v = client.embedQuery("hello world", "mpnet-base-v2");
        assertThat(v).hasSize(EmbeddingModelCatalog.STORAGE_DIMENSIONS);
    }

    @Test
    void preChunkedIsOneBatchAndEchoesModelKey() {
        SidecarEmbedResponse r = client.embedPreChunked(List.of("a", "b", "c"), "minilm-l6-v2");
        assertThat(fake.calls).hasSize(1);
        assertThat(r.model()).isEqualTo("minilm-l6-v2");
        assertThat(r.dimensions()).isEqualTo(3);
        assertThat(r.chunks()).hasSize(3);
        assertThat(r.chunks().get(2).index()).isEqualTo(2);
        assertThat(r.chunks().get(2).text()).isEqualTo("c");
        assertThat(r.chunks().get(0).embedding()).hasSize(EmbeddingModelCatalog.STORAGE_DIMENSIONS);
    }

    @Test
    void embedChunksTextInJavaAndDefaultsModelKey() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("word").append(i).append(' ');
        }
        SidecarEmbedResponse r = client.embed(text.toString(), 20, 5);
        assertThat(r.chunks()).hasSizeGreaterThan(1);
        assertThat(r.model()).isEqualTo(EmbeddingModelCatalog.DEFAULT_KEY);
        assertThat(r.chunkSize()).isEqualTo(20);
        assertThat(r.chunks().get(0).embedding()).hasSize(EmbeddingProperties.UNPADDED_COLUMN_DIMENSIONS);
    }

    @Test
    void blankTextFailsLikeTheSidecar() {
        assertThatThrownBy(() -> client.embedQuery("   ")).isInstanceOf(SidecarCommunicationException.class);
    }
}
