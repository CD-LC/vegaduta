package com.agenticai.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.knowledge.dto.SidecarEmbedResponse;
import com.agenticai.core.knowledge.embedding.EmbeddingClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Width handling of the external embedder path: a vector is padded, truncated only for models that
 * allow it, or refused - never silently reshaped. Plus the provenance fingerprint and the
 * sidecar-off behaviour of {@link SidecarClient}.
 */
class SidecarClientEmbeddingFitTest {

    private static final class Fake implements EmbeddingClient {
        final int width;
        final boolean truncatable;
        int queryCalls;

        Fake(int width, boolean truncatable) {
            this.width = width;
            this.truncatable = truncatable;
        }

        @Override public String provider() { return "fake"; }
        @Override public String modelId() { return "m"; }
        @Override public int dimensions() { return width; }
        @Override public boolean supportsTruncation() { return truncatable; }

        @Override
        public List<float[]> embed(List<String> texts) {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                float[] v = new float[width];
                v[0] = 3f;
                if (width > 1) {
                    v[1] = 4f;
                }
                out.add(v);
            }
            return out;
        }

        @Override
        public List<float[]> embedQuery(List<String> texts) {
            queryCalls++;
            return embed(texts);
        }
    }

    private static SidecarClient client(EmbeddingClient e) {
        return new SidecarClient("http://127.0.0.1:1", "", e);
    }

    @Test
    void aMatryoshkaModelWiderThanTheKnowledgeColumnIsCutAndRenormalised() {
        SidecarClient c = client(new Fake(1024, true));
        SidecarEmbedResponse r = c.embedPreChunked(List.of("a"), "minilm-l6-v2");
        List<Double> v = r.chunks().get(0).embedding();
        assertThat(v).hasSize(EmbeddingModelCatalog.STORAGE_DIMENSIONS);
        assertThat(v.get(0)).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(v.get(1)).isCloseTo(0.8, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(r.dimensions()).isEqualTo(1024); // the model's native width is still reported
    }

    @Test
    void aWideModelThatIsNotTruncatableIsRefusedNotSilentlyCut() {
        SidecarClient c = client(new Fake(1024, false));
        assertThatThrownBy(() -> c.embedPreChunked(List.of("a"), "minilm-l6-v2"))
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("exceeds the target column width 768")
            .hasMessageContaining("not configured for truncation");
        assertThatThrownBy(() -> c.embedQuery("q", "minilm-l6-v2"))
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("exceeds the target column width 768");
    }

    @Test
    void a768ModelFitsKnowledgeButNeedsTruncationForTheMemoryColumn() {
        SidecarClient plain = client(new Fake(768, false));
        assertThat(plain.embedPreChunked(List.of("a"), "mpnet-base-v2").chunks().get(0).embedding()).hasSize(768);
        assertThatThrownBy(() -> plain.embedQuery("remember this"))
            .hasMessageContaining("exceeds the target column width 384");

        SidecarClient matryoshka = client(new Fake(768, true));
        assertThat(matryoshka.embedQuery("remember this")).hasSize(384);
    }

    @Test
    void queryVectorsTakeTheDirectFloatPathAndThePaddingRulesHold() {
        Fake fake = new Fake(3, false);
        SidecarClient c = client(fake);
        float[] v = c.embedQuery("hello", "mpnet-base-v2");
        assertThat(fake.queryCalls).isEqualTo(1);
        assertThat(v).hasSize(768);
        assertThat(v[0]).isEqualTo(3f);
        assertThat(v[3]).isEqualTo(0f);
    }

    @Test
    void fingerprintNamesProviderModelAndWidth() {
        assertThat(client(new Fake(768, true)).embeddingFingerprint()).isEqualTo("fake:m:768");
        assertThat(new SidecarClient("http://127.0.0.1:1", "").embeddingFingerprint()).isEqualTo("sidecar");
    }

    // --- sidecar switched off ---------------------------------------------------------------

    private static SidecarClient sidecarOff() {
        SidecarClient c = new SidecarClient("http://127.0.0.1:1", "");
        c.setSidecarEnabled(false);
        return c;
    }

    @Test
    void embeddingsWithoutSidecarOrProviderAreFeatureUnavailable() {
        SidecarClient c = sidecarOff();
        assertThatThrownBy(() -> c.embedQuery("q"))
            .isInstanceOf(FeatureUnavailableException.class)
            .isInstanceOf(SidecarCommunicationException.class)
            .hasMessageContaining("app.embeddings.provider=ollama");
        assertThatThrownBy(() -> c.embedPreChunked(List.of("a"), "minilm-l6-v2")).isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> c.embed("some text", 200, 0)).isInstanceOf(FeatureUnavailableException.class);
    }

    @Test
    void ollamaEmbeddingsStillWorkWithTheSidecarOff() {
        SidecarClient c = new SidecarClient("http://127.0.0.1:1", "", new Fake(768, true));
        c.setSidecarEnabled(false);
        assertThat(c.embedQuery("q", "minilm-l6-v2")).hasSize(768);
        assertThat(c.embedPreChunked(List.of("a"), "minilm-l6-v2").chunks()).hasSize(1);
    }

    @Test
    void rerankFailsOpenWithoutAConnectionAttempt() {
        assertThat(sidecarOff().rerank("q", List.of("a", "b"))).isNull();
    }

    @Test
    void transcriptionAndMediaAreFeatureUnavailable() {
        SidecarClient c = sidecarOff();
        assertThatThrownBy(() -> c.transcribeAudio("a.mp3", new byte[] {1})).isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> c.transcribeAudioLive("a.mp3", new byte[] {1})).isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> c.tts(java.util.Map.of())).isInstanceOf(FeatureUnavailableException.class);
        assertThatThrownBy(() -> c.renderStatus("j")).isInstanceOf(FeatureUnavailableException.class);
    }

    @Test
    void extractionFallsBackInProcessForHtmlAndSaysUnavailableForPdfAndImages() {
        SidecarClient c = sidecarOff();
        assertThat(c.extractText("page.html", "<html><body><p>Hello &amp; welcome</p></body></html>".getBytes()))
            .isEqualTo("Hello & welcome");
        assertThatThrownBy(() -> c.extractText("a.pdf", new byte[] {1}))
            .isInstanceOf(FeatureUnavailableException.class)
            .hasMessageContaining("PDF");
        assertThatThrownBy(() -> c.extractText("a.png", new byte[] {1}))
            .isInstanceOf(FeatureUnavailableException.class)
            .hasMessageContaining("OCR");
    }
}
