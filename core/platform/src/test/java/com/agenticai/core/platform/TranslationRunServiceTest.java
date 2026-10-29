package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * The on-demand dispatch never throws: an unconfigured deployment, a rejected
 * token and a surprise failure all land as a recorded {@code lastTrigger*} row
 * the console can render - a 500 with no trail is the failure mode this class
 * exists to prevent.
 */
class TranslationRunServiceTest {

    private TranslationJobSettingsService settingsService;
    private RestClient restClient;
    private RestClient.RequestBodyUriSpec request;
    private RestClient.ResponseSpec response;
    private TranslationJobSettings settings;

    @BeforeEach
    void setUp() {
        settingsService = mock(TranslationJobSettingsService.class);
        settings = new TranslationJobSettings(
            "0 6 * * 1", ProviderName.ANTHROPIC, "m",
            ".github/workflows/translate-messages.yml", null);
        when(settingsService.get()).thenReturn(settings);

        restClient = mock(RestClient.class);
        request = mock(RestClient.RequestBodyUriSpec.class);
        response = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(request);
        when(request.uri(anyString())).thenReturn(request);
        when(request.header(anyString(), any(String[].class))).thenReturn(request);
        when(request.contentType(any())).thenReturn(request);
        when(request.body(any(Object.class))).thenReturn(request);
        when(request.retrieve()).thenReturn(response);
    }

    private TranslationRunService service(String repo, String token) {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(restClient);
        return new TranslationRunService(settingsService, builder, repo, token, "https://api.example");
    }

    // ---------------------------------------------------------------- not configured

    @Test
    void anUnconfiguredDeploymentRecordsNotConfiguredAndNeverCallsGitHub() {
        TranslationJobSettings result = service("", "").triggerNow("ops@example.com");

        assertSame(settings, result);
        assertEquals("NOT_CONFIGURED", settings.getLastTriggerStatus());
        assertEquals("ops@example.com", settings.getLastTriggeredBy());
        assertTrue(settings.getLastTriggerMessage().contains("GITHUB_REPO"));
        verify(restClient, org.mockito.Mockito.never()).post();
    }

    @Test
    void aBlankTokenAloneIsStillNotConfigured() {
        service("owner/repo", "  ").triggerNow("ops");

        assertEquals("NOT_CONFIGURED", settings.getLastTriggerStatus());
    }

    // ---------------------------------------------------------------- dispatched

    @Test
    void aConfiguredTriggerPostsTheDispatchToTheWorkflowsEndpoint() {
        when(response.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        service("acme/widgets", "secret-token").triggerNow("ops@example.com");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(request).uri(url.capture());
        assertEquals(
            "https://api.example/repos/acme/widgets/actions/workflows/translate-messages.yml/dispatches",
            url.getValue());
        verify(request).header("Authorization", "Bearer secret-token");
        verify(request).body("{\"ref\":\"main\"}");

        assertEquals("DISPATCHED", settings.getLastTriggerStatus());
    }

    @Test
    void theWorkflowFileComesFromThePathHintNotTheWholePath() {
        settings.applyUpdate(null, null, null, "some/other/dir/custom-workflow.yml", null, "ops");
        when(response.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        service("acme/widgets", "t").triggerNow("ops");

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(request).uri(url.capture());
        assertTrue(url.getValue().endsWith("/actions/workflows/custom-workflow.yml/dispatches"),
            "GitHub wants the file name, not the repository-relative path: " + url.getValue());
    }

    // ---------------------------------------------------------------- failures recorded, not thrown

    @Test
    void a404PointsAtTheRepoAndWorkflowNameConfig() {
        when(request.retrieve()).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        service("acme/widgets", "t").triggerNow("ops");

        assertEquals("FAILED", settings.getLastTriggerStatus());
        assertTrue(settings.getLastTriggerMessage().contains("404"));
        assertTrue(settings.getLastTriggerMessage().contains("github-repo"));
    }

    @Test
    void a401Or403ExplainsTheTokenScopeItNeeds() {
        when(request.retrieve()).thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

        service("acme/widgets", "t").triggerNow("ops");

        assertEquals("FAILED", settings.getLastTriggerStatus());
        assertTrue(settings.getLastTriggerMessage().contains("401/403"));
        assertTrue(settings.getLastTriggerMessage().contains("Actions:write"));
    }

    @Test
    void anyOtherUpstreamStatusRecordsTheHttpLine() {
        when(request.retrieve()).thenThrow(
            new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "bad gateway"));

        service("acme/widgets", "t").triggerNow("ops");

        assertEquals("FAILED", settings.getLastTriggerStatus());
        assertTrue(settings.getLastTriggerMessage().contains("502"));
    }

    @Test
    void anUnexpectedErrorStillLandsOnTheRow() {
        when(request.retrieve()).thenThrow(new IllegalStateException("dns exploded"));

        service("acme/widgets", "t").triggerNow("ops");

        assertEquals("FAILED", settings.getLastTriggerStatus());
        assertTrue(settings.getLastTriggerMessage().contains("dns exploded"));
    }
}
