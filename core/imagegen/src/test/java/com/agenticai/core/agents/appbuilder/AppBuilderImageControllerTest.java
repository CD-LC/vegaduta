package com.agenticai.core.agents.appbuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.imagegen.ImageGenerationOrchestratorService;
import com.agenticai.core.imagegen.agenttools.OrchestratorImageGenerator;
import com.agenticai.core.json.JsonMappers;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AppBuilderImageControllerTest {

    private final ImageGenerationOrchestratorService orchestrator =
            mock(ImageGenerationOrchestratorService.class);

    private AppBuilderImageController controller(String token) {
        return new AppBuilderImageController(
                new OrchestratorImageGenerator(orchestrator), token, JsonMappers.plain());
    }

    @Test
    void failsClosedWhenTokenUnconfigured() {
        assertThrows(
                AppBuilderTokenMissingException.class,
                () -> controller("").generateImage("x", "t1", "{\"prompt\":\"a\"}"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void rejectsWrongToken() {
        assertThrows(
                AppBuilderTokenInvalidException.class,
                () -> controller("secret").generateImage("nope", "t1", "{\"prompt\":\"a\"}"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void rejectsBlankPrompt() {
        assertThrows(
                AppBuilderValidationException.class,
                () -> controller("secret").generateImage("secret", "t1", "{\"prompt\":\"  \"}"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void returnsBase64BytesFromOrchestrator() {
        byte[] bytes = {1, 2, 3};
        when(orchestrator.generate(any(), any()))
                .thenReturn(
                        new ImageGenerationOrchestratorService.GeneratedImageResult(
                                bytes, "image/png", "m", "OPENAI"));
        ResponseEntity<Map<String, Object>> r =
                controller("secret")
                        .generateImage(
                                "secret", "t1", "{\"prompt\":\"a fox\",\"size\":\"1024x1024\"}");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(Base64.getEncoder().encodeToString(bytes), r.getBody().get("contentBase64"));
        verify(orchestrator).generate("a fox", "1024x1024");
    }

    @Test
    void noProviderIs409WithCode() {
        when(orchestrator.generate(any(), any()))
                .thenThrow(new ImageGenNotConfiguredException("none"));
        ResponseEntity<Map<String, Object>> r =
                controller("secret").generateImage("secret", "t1", "{\"prompt\":\"a fox\"}");
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("no_image_provider", r.getBody().get("code"));
    }
}
