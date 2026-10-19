package com.agenticai.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.knowledge.ocr.ActiveOcrConfig;
import com.agenticai.core.knowledge.ocr.OcrProvider;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * {@link SidecarClient} is the sole HTTP boundary between the Java core and
 * the Python sidecar - every embed, extract, transcribe, and rerank call
 * the RAG pipeline makes goes through it, and it had zero coverage despite
 * carrying real, non-obvious behavior in its own javadoc: the {@code
 * embed}-not-{@code embed-query} reuse for {@link #embedQuery}, a
 * documented chunked-transfer-encoding interop workaround in the
 * constructor, and a deliberate 4xx-vs-everything-else exception split
 * ({@link InvalidKnowledgeUploadException} for a bad file vs {@link
 * SidecarCommunicationException} for "the sidecar itself is broken") on
 * {@link #extractText} / {@link #transcribeAudio}. {@link #rerank} is the
 * one method that fails open (returns {@code null} rather than throwing),
 * mirrored by {@link KnowledgeSearchService#rerankIfEnabled}'s own
 * fail-open handling.
 *
 * <p>Uses {@link MockRestServiceServer} the same way {@code
 * ProviderModelCatalogServiceTest} does (Tier 1's first {@code RestClient}
 * suite) - stub the HTTP response, exercise the real request-building code -
 * but with one difference forced by this class's shape: {@link
 * SidecarClient}'s constructor takes only a base-URL {@code String} and
 * builds its own internal {@link RestClient}s (with a custom {@link
 * org.springframework.http.client.SimpleClientHttpRequestFactory}), unlike
 * {@code ProviderModelCatalogService} which accepts an injectable {@code
 * RestClient.Builder}. So this suite combines that pattern with the
 * reflection-field-injection technique {@code S3FileStorageServiceTest}
 * introduced for the same reason ({@code S3FileStorageService} also builds
 * its own client internally): construct the real {@link SidecarClient}
 * (safe - the constructor only assembles clients, it never connects), then
 * reflectively replace its private final per-operation client fields
 * ({@code queryRestClient}/{@code ingestRestClient}/{@code
 * transcriptionRestClient} - three fields since the RAG-audit timeout fix
 * gave each call class its own read timeout, see {@link SidecarClient}'s
 * class javadoc) with one bound to a shared {@link MockRestServiceServer}.
 */
class SidecarClientTest {

    private static final String BASE_URL = "http://sidecar.internal";
    private static final String SHARED_SECRET = "test-shared-secret";

    private SidecarClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() throws Exception {
        client = new SidecarClient(BASE_URL, SHARED_SECRET);
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient mockedRestClient = builder.build();

        for (String fieldName : List.of("queryRestClient", "ingestRestClient", "transcriptionRestClient")) {
            Field restClientField = SidecarClient.class.getDeclaredField(fieldName);
            restClientField.setAccessible(true);
            restClientField.set(client, mockedRestClient);
        }
    }

    // --- internal auth header -------------------------------------------------

    @Test
    void everyCallCarriesTheInternalTokenHeaderWhenASecretIsConfigured() {
        // One expectation per endpoint family - /embed covers embedQuery too
        // (it reuses embed, per its javadoc), and /transcribe covers both
        // transcribeAudio and transcribeAudioLive (same private helper).
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andExpect(header("X-Internal-Token", SHARED_SECRET))
            .andRespond(withSuccess("""
                {"model":"minilm-l6-v2","dimensions":768,"chunk_size":500,"chunk_overlap":0,"chunks":[]}
                """, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(BASE_URL + "/extract"))
            .andExpect(header("X-Internal-Token", SHARED_SECRET))
            .andRespond(withSuccess("{\"text\":\"t\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(BASE_URL + "/transcribe"))
            .andExpect(header("X-Internal-Token", SHARED_SECRET))
            .andRespond(withSuccess("{\"text\":\"t\"}", MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo(BASE_URL + "/rerank"))
            .andExpect(header("X-Internal-Token", SHARED_SECRET))
            .andRespond(withSuccess("{\"scores\":[0.1]}", MediaType.APPLICATION_JSON));

        client.embed("text", 500, 0);
        client.extractText("doc.pdf", "bytes".getBytes());
        client.transcribeAudio("clip.mp3", "bytes".getBytes());
        client.rerank("q", List.of("doc"));

        mockServer.verify();
    }

    @Test
    void aBlankSecretSendsNoInternalTokenHeaderAtAll() throws Exception {
        // Absent, not empty - the sidecar's SIDECAR_AUTH_REQUIRED=false local
        // dev mode should see wire traffic identical to the pre-auth client.
        SidecarClient tokenlessClient = new SidecarClient(BASE_URL, "  ");
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer tokenlessServer = MockRestServiceServer.bindTo(builder).build();
        RestClient mockedRestClient = builder.build();
        for (String fieldName : List.of("queryRestClient", "ingestRestClient", "transcriptionRestClient")) {
            Field restClientField = SidecarClient.class.getDeclaredField(fieldName);
            restClientField.setAccessible(true);
            restClientField.set(tokenlessClient, mockedRestClient);
        }
        tokenlessServer.expect(requestTo(BASE_URL + "/embed"))
            .andExpect(headerDoesNotExist("X-Internal-Token"))
            .andRespond(withSuccess("""
                {"model":"minilm-l6-v2","dimensions":768,"chunk_size":500,"chunk_overlap":0,"chunks":[]}
                """, MediaType.APPLICATION_JSON));

        tokenlessClient.embed("text", 500, 0);

        tokenlessServer.verify();
    }

    // --- embed() / embedQuery() ---------------------------------------------

    @Test
    void embedSendsChunkSizeAndOverlapAndParsesTheChunkList() {
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.text").value("hello world"))
            .andExpect(jsonPath("$.chunk_size").value(500))
            .andExpect(jsonPath("$.chunk_overlap").value(50))
            .andRespond(withSuccess("""
                {"model":"minilm-l6-v2","dimensions":768,"chunk_size":500,"chunk_overlap":50,
                 "chunks":[{"index":0,"text":"hello world","embedding":[0.1,0.2,0.3]}]}
                """, MediaType.APPLICATION_JSON));

        var response = client.embed("hello world", 500, 50);

        assertEquals("minilm-l6-v2", response.model());
        assertEquals(768, response.dimensions());
        assertEquals(1, response.chunks().size());
    }

    @Test
    void embedThreeArgOverloadOmitsAModelSelectionSoTheSidecarPicksItsOwnDefault() {
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andExpect(jsonPath("$.model").doesNotExist())
            .andRespond(withSuccess("""
                {"model":"minilm-l6-v2","dimensions":768,"chunk_size":500,"chunk_overlap":0,"chunks":[]}
                """, MediaType.APPLICATION_JSON));

        client.embed("text", 500, 0);
    }

    @Test
    void embedFourArgOverloadThreadsAnExplicitEmbeddingModelThrough() {
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andExpect(jsonPath("$.model").value("bge-small-en"))
            .andRespond(withSuccess("""
                {"model":"bge-small-en","dimensions":768,"chunk_size":500,"chunk_overlap":0,"chunks":[]}
                """, MediaType.APPLICATION_JSON));

        var response = client.embed("text", 500, 0, "bge-small-en");

        assertEquals("bge-small-en", response.model());
    }

    @Test
    void embedThrowsSidecarCommunicationExceptionWhenTheBodyDeserializesToNull() {
        mockServer.expect(requestTo(BASE_URL + "/embed")).andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        SidecarCommunicationException ex = assertThrows(
            SidecarCommunicationException.class, () -> client.embed("text", 500, 0)
        );
        assertTrue(ex.getMessage().contains("empty response"));
    }

    @Test
    void embedWrapsAServerErrorResponseWithItsStatusAndBodyInTheDescription() {
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andRespond(withServerError().body("{\"detail\":\"OOM loading model\"}"));

        SidecarCommunicationException ex = assertThrows(
            SidecarCommunicationException.class, () -> client.embed("text", 500, 0)
        );
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.getMessage().contains("OOM loading model"));
    }

    @Test
    void embedWrapsAGenuineConnectionFailureWithTheCouldNotReachMessage() {
        // Deliberately does NOT use the MockRestServiceServer-bound client from
        // setUp() - MockRestServiceServer intercepts every request, so it can
        // never produce the "connection refused" branch of SidecarClient's
        // private describe() helper (the RestClientResponseException-vs-not
        // check). Pointing at a real, guaranteed-closed local port (0 -> OS
        // picks an ephemeral one that is closed the instant the ServerSocket
        // below is closed) exercises that branch without any external network
        // dependency - the connection fails locally and immediately.
        SidecarClient unmockedClient;
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        unmockedClient = new SidecarClient("http://127.0.0.1:" + closedPort, SHARED_SECRET);

        SidecarCommunicationException ex = assertThrows(
            SidecarCommunicationException.class, () -> unmockedClient.embed("text", 500, 0)
        );
        assertTrue(ex.getMessage().contains("is it running"));
    }

    @Test
    void embedQueryReusesEmbedWithA200CharChunkSizeAndZeroOverlapPerItsOwnJavadoc() {
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andExpect(jsonPath("$.chunk_size").value(200))
            .andExpect(jsonPath("$.chunk_overlap").value(0))
            .andRespond(withSuccess("""
                {"model":"minilm-l6-v2","dimensions":768,"chunk_size":200,"chunk_overlap":0,
                 "chunks":[{"index":0,"text":"q","embedding":[1.0,2.0,3.5]}]}
                """, MediaType.APPLICATION_JSON));

        float[] vector = client.embedQuery("q");

        assertArrayEquals(new float[]{1.0f, 2.0f, 3.5f}, vector);
    }

    @Test
    void embedQueryPassesTheEmbeddingModelThroughToTheUnderlyingEmbedCall() {
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andExpect(jsonPath("$.model").value("mpnet-base-v2"))
            .andRespond(withSuccess("""
                {"model":"mpnet-base-v2","dimensions":768,"chunk_size":200,"chunk_overlap":0,
                 "chunks":[{"index":0,"text":"q","embedding":[0.5]}]}
                """, MediaType.APPLICATION_JSON));

        client.embedQuery("q", "mpnet-base-v2");
    }

    @Test
    void embedQueryThrowsWhenTheSidecarReturnsNoChunksForTheQueryText() {
        mockServer.expect(requestTo(BASE_URL + "/embed"))
            .andRespond(withSuccess("""
                {"model":"minilm-l6-v2","dimensions":768,"chunk_size":200,"chunk_overlap":0,"chunks":[]}
                """, MediaType.APPLICATION_JSON));

        SidecarCommunicationException ex = assertThrows(SidecarCommunicationException.class, () -> client.embedQuery("q"));
        assertTrue(ex.getMessage().contains("no chunks"));
    }

    // --- extractText() -------------------------------------------------------

    @Test
    void extractTextTwoArgOverloadSendsNoOcrProviderFields() {
        mockServer.expect(requestTo(BASE_URL + "/extract"))
            .andExpect(jsonPath("$.ocr_provider").doesNotExist())
            .andExpect(jsonPath("$.ocr_api_key").doesNotExist())
            .andExpect(jsonPath("$.content_base64").exists())
            .andRespond(withSuccess("{\"text\":\"extracted plain text\"}", MediaType.APPLICATION_JSON));

        String text = client.extractText("doc.pdf", "raw bytes".getBytes());

        assertEquals("extracted plain text", text);
    }

    @Test
    void extractTextWithAnActiveOcrConfigThreadsProviderAndApiKeyThrough() {
        mockServer.expect(requestTo(BASE_URL + "/extract"))
            .andExpect(jsonPath("$.ocr_provider").value("GOOGLE_VISION"))
            .andExpect(jsonPath("$.ocr_api_key").value("secret-key"))
            .andRespond(withSuccess("{\"text\":\"ocr'd text\"}", MediaType.APPLICATION_JSON));

        String text = client.extractText("scan.png", "bytes".getBytes(), new ActiveOcrConfig(OcrProvider.GOOGLE_VISION, "secret-key"));

        assertEquals("ocr'd text", text);
    }

    @Test
    void extractTextWithANullOcrConfigBehavesLikeTheTwoArgOverload() {
        mockServer.expect(requestTo(BASE_URL + "/extract"))
            .andExpect(jsonPath("$.ocr_provider").doesNotExist())
            .andRespond(withSuccess("{\"text\":\"t\"}", MediaType.APPLICATION_JSON));

        client.extractText("doc.pdf", "bytes".getBytes(), null);
    }

    @Test
    void extractTextTranslatesAClientErrorIntoInvalidKnowledgeUploadExceptionUsingTheDetailField() {
        mockServer.expect(requestTo(BASE_URL + "/extract"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("{\"detail\":\"Unsupported file extension .xyz\"}")
                .contentType(MediaType.APPLICATION_JSON));

        InvalidKnowledgeUploadException ex = assertThrows(
            InvalidKnowledgeUploadException.class, () -> client.extractText("file.xyz", "bytes".getBytes())
        );
        assertEquals("Unsupported file extension .xyz", ex.getMessage());
    }

    @Test
    void extractTextFallsBackToAGenericMessageWhenAClientErrorBodyIsBlank() {
        mockServer.expect(requestTo(BASE_URL + "/extract"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(""));

        InvalidKnowledgeUploadException ex = assertThrows(
            InvalidKnowledgeUploadException.class, () -> client.extractText("file.pdf", "bytes".getBytes())
        );
        assertTrue(ex.getMessage().contains("400"));
    }

    @Test
    void extractTextFallsBackToTheRawBodyWhenItIsNotJson() {
        mockServer.expect(requestTo(BASE_URL + "/extract"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("plain text error, not json"));

        InvalidKnowledgeUploadException ex = assertThrows(
            InvalidKnowledgeUploadException.class, () -> client.extractText("file.pdf", "bytes".getBytes())
        );
        assertEquals("plain text error, not json", ex.getMessage());
    }

    @Test
    void extractTextTreatsAServerErrorAsSidecarCommunicationExceptionNotInvalidUpload() {
        // A 5xx means the sidecar itself broke, not that the uploaded file was
        // bad - must NOT become an InvalidKnowledgeUploadException.
        mockServer.expect(requestTo(BASE_URL + "/extract")).andRespond(withServerError());

        assertThrows(SidecarCommunicationException.class, () -> client.extractText("file.pdf", "bytes".getBytes()));
    }

    // --- transcribeAudio() ----------------------------------------------------

    @Test
    void transcribeAudioReturnsTheTranscribedText() {
        mockServer.expect(requestTo(BASE_URL + "/transcribe"))
            .andExpect(jsonPath("$.content_base64").exists())
            .andRespond(withSuccess("{\"text\":\"hello from the recording\"}", MediaType.APPLICATION_JSON));

        assertEquals("hello from the recording", client.transcribeAudio("voicemail.mp3", "audio bytes".getBytes()));
    }

    @Test
    void transcribeAudioTranslatesAClientErrorIntoInvalidKnowledgeUploadException() {
        mockServer.expect(requestTo(BASE_URL + "/transcribe"))
            .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                .body("{\"detail\":\"Corrupt audio file\"}")
                .contentType(MediaType.APPLICATION_JSON));

        InvalidKnowledgeUploadException ex = assertThrows(
            InvalidKnowledgeUploadException.class, () -> client.transcribeAudio("bad.mp3", "bytes".getBytes())
        );
        assertEquals("Corrupt audio file", ex.getMessage());
    }

    @Test
    void transcribeAudioTreatsAServerErrorAsSidecarCommunicationException() {
        mockServer.expect(requestTo(BASE_URL + "/transcribe")).andRespond(withServerError());

        assertThrows(SidecarCommunicationException.class, () -> client.transcribeAudio("file.mp3", "bytes".getBytes()));
    }

    // --- rerank(): the one fail-open method ------------------------------------

    @Test
    void rerankReturnsAnEmptyListImmediatelyForNoCandidatesWithoutCallingTheSidecar() {
        List<Double> scores = client.rerank("query", List.of());

        assertEquals(List.of(), scores);
        mockServer.verify();
    }

    @Test
    void rerankReturnsScoresInTheSameOrderTheyWereRequested() {
        mockServer.expect(requestTo(BASE_URL + "/rerank"))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andRespond(withSuccess("{\"scores\":[0.9,0.2,0.55]}", MediaType.APPLICATION_JSON));

        List<Double> scores = client.rerank("query", List.of("doc a", "doc b", "doc c"));

        assertEquals(List.of(0.9, 0.2, 0.55), scores);
    }

    @Test
    void rerankFailsOpenReturningNullRatherThanThrowingWhenTheSidecarErrors() {
        mockServer.expect(requestTo(BASE_URL + "/rerank")).andRespond(withServerError());

        List<Double> scores = client.rerank("query", List.of("doc a"));

        assertNull(scores);
    }
}
