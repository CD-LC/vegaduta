package com.agenticai.core.agents.appbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.ImageGenerator;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantStatusGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

/** The two service-to-service app-builder endpoints: gate order, retry rule, image mapping. */
class AppBuilderControllersTest {

    private static final String TOKEN = "service-token";
    private static final String BODY =
            "{\"instruction\":\"add a footer\",\"retrievedFiles\":[{\"path\":\"a.ts\",\"content\":\"x\"}]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private AppBuilderCompletionService completion;
    private ImageGenerator images;
    private TenantStatusGuard guard;
    private AppBuilderCompletionController files;
    private AppBuilderImageController image;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        completion = mock(AppBuilderCompletionService.class);
        images = mock(ImageGenerator.class);
        guard = mock(TenantStatusGuard.class);
        when(guard.isUsable(anyString())).thenReturn(true);
        ObjectProvider<TenantStatusGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        files =
                new AppBuilderCompletionController(
                        completion, TOKEN, "acme", mapper, validator, provider);
        image = new AppBuilderImageController(images, TOKEN, "acme", mapper, provider);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static AppBuilderGeneration generation(int fileCount) {
        List<AppBuilderFileEdit> edits =
                fileCount == 0 ? List.of() : List.of(new AppBuilderFileEdit("a.ts", "y"));
        return new AppBuilderGeneration(edits, "openai", "gpt-x", "Added a footer");
    }

    @Test
    void generateFilesChecksTheTokenBeforeLookingAtTheBody() {
        assertThatThrownBy(() -> files.generateFiles("wrong", "acme", "{not json"))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
        assertThatThrownBy(() -> files.generateFiles(null, "acme", BODY))
                .isInstanceOf(AppBuilderTokenMissingException.class);
        verify(completion, never()).generateFiles(anyString(), any());
    }

    @Test
    void generateFilesNeedsATenantThatIsOnTheAllowList() {
        assertThatThrownBy(() -> files.generateFiles(TOKEN, " ", BODY))
                .isInstanceOf(AppBuilderTokenMissingException.class)
                .hasMessageContaining("X-Tenant-Id");
        assertThatThrownBy(() -> files.generateFiles(TOKEN, null, BODY))
                .isInstanceOf(AppBuilderTokenMissingException.class);
        assertThatThrownBy(() -> files.generateFiles(TOKEN, "initech", BODY))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
    }

    @Test
    void anUnreadableOrInvalidBodyIsAValidationError() {
        assertThatThrownBy(() -> files.generateFiles(TOKEN, "acme", null))
                .isInstanceOf(AppBuilderValidationException.class)
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> files.generateFiles(TOKEN, "acme", "{not json"))
                .isInstanceOf(AppBuilderValidationException.class);
        assertThatThrownBy(() -> files.generateFiles(TOKEN, "acme", "{\"retrievedFiles\":[]}"))
                .isInstanceOf(AppBuilderValidationException.class)
                .hasMessageContaining("instruction: instruction is required");
        assertThatThrownBy(() -> files.generateFiles(TOKEN, "acme", "{\"instruction\":\"x\"}"))
                .isInstanceOf(AppBuilderValidationException.class)
                .hasMessageContaining("retrievedFiles");
    }

    @Test
    void aTenantTheGuardRefusesNeverReachesTheService() {
        when(guard.isUsable("acme")).thenReturn(false);

        assertThatThrownBy(() -> files.generateFiles(TOKEN, "acme", BODY))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
        verify(completion, never()).generateFiles(anyString(), any());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aSuccessfulGenerationRunsAsTheClaimedTenantAndClearsTheContext() {
        when(completion.generateFiles(eq("acme"), any()))
                .thenAnswer(
                        inv -> {
                            assertThat(TenantContext.current())
                                    .get()
                                    .extracting(TenantContext::getTenantId)
                                    .isEqualTo("acme");
                            return generation(1);
                        });

        AppBuilderGenerateFilesResult result = files.generateFiles(TOKEN, "acme", BODY);

        assertThat(result.files()).hasSize(1);
        assertThat(result.providerName()).isEqualTo("openai");
        assertThat(result.model()).isEqualTo("gpt-x");
        assertThat(result.summary()).isEqualTo("Added a footer");
        verify(completion, times(1)).generateFiles(anyString(), any());
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void anEmptyFirstReplyGetsOneFormatNudgeRetry() {
        when(completion.generateFiles(eq("acme"), any()))
                .thenReturn(generation(0))
                .thenReturn(generation(1));

        AppBuilderGenerateFilesResult result = files.generateFiles(TOKEN, "acme", BODY);

        ArgumentCaptor<AppBuilderGenerateFilesRequest> sent =
                ArgumentCaptor.forClass(AppBuilderGenerateFilesRequest.class);
        verify(completion, times(2)).generateFiles(eq("acme"), sent.capture());
        assertThat(sent.getAllValues().get(0).formatRetry()).isFalse();
        assertThat(sent.getAllValues().get(1).formatRetry()).isTrue();
        assertThat(sent.getAllValues().get(1).previousFailure()).isNull();
        assertThat(result.files()).hasSize(1);
    }

    @Test
    void anEmptyReplyIsNotRetriedAgainWhenTheCallerAlreadySentAFailureOrTheRetryIsEmptyToo() {
        when(completion.generateFiles(eq("acme"), any())).thenReturn(generation(0));

        files.generateFiles(
                TOKEN,
                "acme",
                "{\"instruction\":\"x\",\"retrievedFiles\":[],\"previousFailure\":\"npm test failed\"}");
        verify(completion, times(1)).generateFiles(anyString(), any());

        AppBuilderGenerateFilesResult result = files.generateFiles(TOKEN, "acme", BODY);
        verify(completion, times(3)).generateFiles(anyString(), any());
        assertThat(result.files()).isEmpty();
    }

    @Test
    void imageGenerationEncodesTheBytesAndReportsTheProvider() {
        when(images.generate("a red logo", "512x512"))
                .thenReturn(
                        new ImageGenerator.GeneratedImage(
                                new byte[] {1, 2, 3}, "image/png", "img-1", "vertex"));

        ResponseEntity<Map<String, Object>> response =
                image.generateImage(
                        TOKEN, "acme", "{\"prompt\":\"a red logo\",\"size\":\"512x512\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry(
                        "contentBase64", Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}))
                .containsEntry("contentType", "image/png")
                .containsEntry("model", "img-1")
                .containsEntry("provider", "vertex");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aMissingSizeIsPassedAsNull() {
        when(images.generate(anyString(), any()))
                .thenReturn(new ImageGenerator.GeneratedImage(new byte[0], "image/png", "m", "p"));

        image.generateImage(TOKEN, "acme", "{\"prompt\":\"logo\",\"size\":null}");

        verify(images).generate("logo", null);
    }

    @Test
    void imageRequestsAreGatedLikeTheFilesEndpoint() {
        assertThatThrownBy(() -> image.generateImage("bad", "acme", "{}"))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
        assertThatThrownBy(() -> image.generateImage(TOKEN, "", "{}"))
                .isInstanceOf(AppBuilderTokenMissingException.class);
        assertThatThrownBy(() -> image.generateImage(TOKEN, "initech", "{\"prompt\":\"x\"}"))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
        when(guard.isUsable("acme")).thenReturn(false);
        assertThatThrownBy(() -> image.generateImage(TOKEN, "acme", "{\"prompt\":\"x\"}"))
                .isInstanceOf(AppBuilderTokenInvalidException.class);
        verify(images, never()).generate(anyString(), any());
    }

    @Test
    void imagePromptsMustBePresentShortAndValidJson() {
        assertThatThrownBy(() -> image.generateImage(TOKEN, "acme", null))
                .isInstanceOf(AppBuilderValidationException.class);
        assertThatThrownBy(() -> image.generateImage(TOKEN, "acme", "{oops"))
                .isInstanceOf(AppBuilderValidationException.class)
                .hasMessageContaining("not valid JSON");
        assertThatThrownBy(() -> image.generateImage(TOKEN, "acme", "{\"prompt\":\"  \"}"))
                .isInstanceOf(AppBuilderValidationException.class)
                .hasMessageContaining("4000");
        assertThatThrownBy(
                        () ->
                                image.generateImage(
                                        TOKEN, "acme", "{\"prompt\":\"" + "p".repeat(4001) + "\"}"))
                .isInstanceOf(AppBuilderValidationException.class);
    }

    @Test
    void noImageProviderIsA409WithAStableCode() {
        when(images.generate(anyString(), any()))
                .thenThrow(new ImageGenerator.NotConfiguredException("no provider", null));

        ResponseEntity<Map<String, Object>> response =
                image.generateImage(TOKEN, "acme", "{\"prompt\":\"logo\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
                .containsEntry("code", "no_image_provider")
                .containsEntry("error", "no provider");
        assertThat(TenantContext.current()).isEmpty();
    }
}
