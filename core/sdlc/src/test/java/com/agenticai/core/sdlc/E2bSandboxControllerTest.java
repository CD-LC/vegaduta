package com.agenticai.core.sdlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sdlc.dto.CreateSandboxRequest;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.sdlc.dto.SandboxResponse;
import com.agenticai.core.sdlc.dto.SandboxSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * First test coverage for {@link E2bSandboxController} - the module had no
 * {@code src/test} directory at all before this. Plain unit tests calling
 * the controller directly with a mocked {@link SdlcSandboxService} (the
 * module's only collaborator), same convention as {@code
 * EdgeToolExecutionControllerTest}/{@code QualityControllerTest}: no Spring
 * context, exceptions from the service are asserted to propagate untouched
 * so {@code GlobalExceptionHandler} (which already maps {@link
 * E2bApiException} to 502 and {@link E2bNotConfiguredException} to 400 -
 * see core/app's handler) is the thing that turns them into a clean
 * response rather than a raw 500 leak.
 */
class E2bSandboxControllerTest {

    private SdlcSandboxService sandboxService;
    private E2bSandboxController controller;

    @BeforeEach
    void setUp() {
        sandboxService = mock(SdlcSandboxService.class);
        controller = new E2bSandboxController(sandboxService);
    }

    // ------------------------------------------------------------------
    // POST /api/sdlc/sandbox - create

    @Test
    void createReturns201WithTheServiceResponseBody() {
        CreateSandboxRequest request = new CreateSandboxRequest("base", 60, Map.of("FOO", "bar"), Map.of("purpose", "manual-test"));
        SandboxResponse serviceResponse = new SandboxResponse("sbx-123", "base", "1.2.3");
        when(sandboxService.create(request)).thenReturn(serviceResponse);

        ResponseEntity<SandboxResponse> response = controller.create(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("sbx-123", response.getBody().sandboxId());
        assertEquals("base", response.getBody().templateId());
        assertEquals("1.2.3", response.getBody().envdVersion());
    }

    @Test
    void createPropagatesE2bApiExceptionForGlobalExceptionHandlerToMapTo502() {
        CreateSandboxRequest request = new CreateSandboxRequest("base", 60, null, null);
        when(sandboxService.create(request)).thenThrow(new E2bApiException(401, "E2B create-sandbox call failed: HTTP 401: invalid API key"));

        E2bApiException thrown = assertThrows(E2bApiException.class, () -> controller.create(request));
        assertEquals(401, thrown.getUpstreamStatus());
        // Consumer-safe degradation check: the raw upstream detail is carried on the
        // exception for GlobalExceptionHandler's 502 mapping, not swallowed into a
        // generic 500 - this is the "clean error response, not a raw 500 leak" bar.
        assertTrue(thrown.getMessage().contains("HTTP 401"));
    }

    // ------------------------------------------------------------------
    // GET /api/sdlc/sandbox - list

    @Test
    void listReturns200WithTheServiceSummaries() {
        List<SandboxSummaryResponse> summaries = List.of(
            new SandboxSummaryResponse("sbx-1", "base", "running", Instant.parse("2026-08-05T00:00:00Z"), null),
            new SandboxSummaryResponse("sbx-2", "base", "stopped", Instant.parse("2026-08-04T00:00:00Z"), Instant.parse("2026-08-04T00:05:00Z"))
        );
        when(sandboxService.list()).thenReturn(summaries);

        ResponseEntity<List<SandboxSummaryResponse>> response = controller.list(null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        assertEquals("sbx-1", response.getBody().get(0).sandboxId());
    }

    @Test
    void listReturns200WithEmptyListWhenNoneAreRunning() {
        when(sandboxService.list()).thenReturn(List.of());

        ResponseEntity<List<SandboxSummaryResponse>> response = controller.list(null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void listPropagatesE2bApiExceptionOnABadCredential() {
        when(sandboxService.list()).thenThrow(new E2bApiException(401, "E2B list-sandboxes call failed: HTTP 401"));

        assertThrows(E2bApiException.class, () -> controller.list(null, null));
    }

    // ------------------------------------------------------------------
    // DELETE /api/sdlc/sandbox/{sandboxId} - kill

    @Test
    void killReturns204AndDelegatesTheSandboxIdVerbatim() {
        ResponseEntity<Void> response = controller.kill("sbx-123");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertNull(response.getBody());
        verify(sandboxService, times(1)).kill(eq("sbx-123"));
    }

    @Test
    void killPropagatesE2bApiExceptionWhenTheSandboxIsAlreadyGone() {
        org.mockito.Mockito.doThrow(new E2bApiException(404, "E2B kill-sandbox call failed: HTTP 404"))
            .when(sandboxService).kill("sbx-already-gone");

        assertThrows(E2bApiException.class, () -> controller.kill("sbx-already-gone"));
    }

    // ------------------------------------------------------------------
    // POST /api/sdlc/sandbox/run-code

    @Test
    void runCodeReturns200WithStdoutStderrExitCode() {
        RunCodeRequest request = new RunCodeRequest("print('hello')", "python", 30);
        RunCodeResponse serviceResponse = new RunCodeResponse("hello\n", "", 0, false, "sbx-run-1");
        when(sandboxService.runCode(request)).thenReturn(serviceResponse);

        ResponseEntity<RunCodeResponse> response = controller.runCode(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("hello\n", response.getBody().stdout());
        assertEquals("", response.getBody().stderr());
        assertEquals(0, response.getBody().exitCode());
        assertEquals(false, response.getBody().timedOut());
        assertEquals("sbx-run-1", response.getBody().sandboxId());
    }

    @Test
    void runCodePropagatesE2bNotConfiguredExceptionWhenNoTemplateIsSetForGlobalExceptionHandlerToMapTo400() {
        RunCodeRequest request = new RunCodeRequest("print('hi')", "python", null);
        when(sandboxService.runCode(request))
            .thenThrow(new E2bNotConfiguredException("No code-execution template configured for this tenant yet."));

        E2bNotConfiguredException thrown = assertThrows(E2bNotConfiguredException.class, () -> controller.runCode(request));
        assertEquals("No code-execution template configured for this tenant yet.", thrown.getMessage());
    }

    @Test
    void runCodePropagatesE2bApiExceptionWhenTheUnderlyingE2bCallFailsRatherThanLeakingA500() {
        // The underlying E2bCodeExecutionClient/E2bSandboxClient calls always translate
        // failures into E2bApiException (never a raw RestClientException) - this asserts
        // the controller doesn't swallow or rewrap that into something else, it just lets
        // it through for GlobalExceptionHandler's existing 502 mapping to handle.
        RunCodeRequest request = new RunCodeRequest("while True: pass", "python", 5);
        when(sandboxService.runCode(request))
            .thenThrow(new E2bApiException(0, "Could not reach the code-execution template's /run endpoint at https://8080-sbx-run-2.e2b.app/run within 8s"));

        E2bApiException thrown = assertThrows(E2bApiException.class, () -> controller.runCode(request));
        assertEquals(0, thrown.getUpstreamStatus());
        verify(sandboxService, never()).kill(any());
    }
}
