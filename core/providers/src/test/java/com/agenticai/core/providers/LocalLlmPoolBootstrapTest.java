package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.providers.LocalLlmPoolBootstrap.Outcome;
import com.agenticai.core.providers.validation.ProviderValidationException;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LocalLlmPoolBootstrapTest {

    private static final String BASE = "http://ollama:11434";
    private static final String TAGS =
            "{\"models\":[{\"name\":\"llama3.2:latest\",\"model\":\"llama3.2:latest\"}]}";

    private PlatformHostedLlmProviderService service;
    private PlatformHostedLlmProviderRepository repository;
    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        service = mock(PlatformHostedLlmProviderService.class);
        repository = mock(PlatformHostedLlmProviderRepository.class);
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(List.of());
        logger = (Logger) LoggerFactory.getLogger(LocalLlmPoolBootstrap.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    /** Hosts resolve to a public address, except "ollama" which is private (compose DNS). */
    private static SsrfGuard guard(Set<String> allowedPrivateHosts) {
        return SsrfGuard.of(
                allowedPrivateHosts,
                host ->
                        new InetAddress[] {
                            InetAddress.getByAddress(host, new byte[] {10, 0, 0, 5})
                        });
    }

    private LocalLlmPoolBootstrap bootstrap(String apiKey, SsrfGuard guard) {
        LocalLlmPoolBootstrap bootstrap =
                new LocalLlmPoolBootstrap(
                        service,
                        repository,
                        builder,
                        BASE,
                        "llama3.2",
                        "Local model (Ollama)",
                        apiKey,
                        0L);
        bootstrap.setSsrfGuard(guard);
        return bootstrap;
    }

    private PlatformHostedLlmProviderResponse created(long id) {
        PlatformHostedLlmProviderResponse response = mock(PlatformHostedLlmProviderResponse.class);
        when(response.id()).thenReturn(id);
        return response;
    }

    private PlatformHostedLlmProvider row(long id, String label, String baseUrl, int priority) {
        PlatformHostedLlmProvider row = mock(PlatformHostedLlmProvider.class);
        when(row.getId()).thenReturn(id);
        when(row.getLabel()).thenReturn(label);
        when(row.getBaseUrl()).thenReturn(baseUrl);
        when(row.getPriority()).thenReturn(priority);
        return row;
    }

    private boolean logged(Level level, String fragment) {
        return logs.list.stream()
                .anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment));
    }

    @Test
    void disabledByDefaultRegistersNoBean() {
        new ApplicationContextRunner()
                .withBean(PlatformHostedLlmProviderService.class, () -> service)
                .withBean(PlatformHostedLlmProviderRepository.class, () -> repository)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(LocalLlmPoolBootstrap.class)
                .run(ctx -> assertFalse(ctx.containsBean("localLlmPoolBootstrap")));
    }

    @Test
    void enabledPropertyRegistersTheBean() {
        new ApplicationContextRunner()
                .withBean(PlatformHostedLlmProviderService.class, () -> service)
                .withBean(PlatformHostedLlmProviderRepository.class, () -> repository)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues("app.local-llm.bootstrap.enabled=true")
                .withUserConfiguration(LocalLlmPoolBootstrap.class)
                .run(ctx -> assertTrue(ctx.containsBean("localLlmPoolBootstrap")));
    }

    @Test
    void createsEnabledTopPriorityRowWithTheKey() {
        server.expect(requestTo(BASE + "/api/tags"))
                .andRespond(withSuccess(TAGS, MediaType.APPLICATION_JSON));
        List<PlatformHostedLlmProvider> others =
                List.of(row(1, "Groq", "https://api.groq.com/openai", 10));
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(others);
        PlatformHostedLlmProviderResponse made = created(2);
        when(service.createAllowingBlankKey(any())).thenReturn(made);

        Outcome outcome = bootstrap("ollama", guard(Set.of("ollama:11434"))).bootstrapOnce();

        assertEquals(Outcome.CREATED, outcome);
        ArgumentCaptor<PlatformHostedLlmProviderUpsertRequest> req =
                ArgumentCaptor.forClass(PlatformHostedLlmProviderUpsertRequest.class);
        verify(service).createAllowingBlankKey(req.capture());
        assertEquals("Local model (Ollama)", req.getValue().label());
        assertEquals(BASE, req.getValue().baseUrl());
        assertEquals("llama3.2", req.getValue().model());
        assertEquals("ollama", req.getValue().apiKey());
        assertTrue(req.getValue().enabled());
        assertEquals(9, req.getValue().priority());
        assertEquals(Boolean.TRUE, req.getValue().zeroCost(), "a local model is free (V484)");
        assertFalse(logged(Level.WARN, "ollama pull"));
    }

    @Test
    void emptyApiKeyCreatesAKeylessRow() {
        server.expect(requestTo(BASE + "/api/tags"))
                .andRespond(withSuccess(TAGS, MediaType.APPLICATION_JSON));
        PlatformHostedLlmProviderResponse made = created(2);
        when(service.createAllowingBlankKey(any())).thenReturn(made);

        assertEquals(Outcome.CREATED, bootstrap("", guard(Set.of("ollama:11434"))).bootstrapOnce());

        ArgumentCaptor<PlatformHostedLlmProviderUpsertRequest> req =
                ArgumentCaptor.forClass(PlatformHostedLlmProviderUpsertRequest.class);
        verify(service).createAllowingBlankKey(req.capture());
        assertEquals(null, req.getValue().apiKey());
    }

    @Test
    void secondRunWithSameLabelOrBaseUrlWritesNothing() {
        List<PlatformHostedLlmProvider> byLabel =
                List.of(row(5, "Local model (Ollama)", "http://other:1", 1));
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(byLabel);
        assertEquals(
                Outcome.ALREADY_PRESENT,
                bootstrap("ollama", guard(Set.of("ollama:11434"))).bootstrapOnce());

        List<PlatformHostedLlmProvider> byUrl =
                List.of(row(6, "Renamed by admin", "http://ollama:11434/v1/", 1));
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(byUrl);
        assertEquals(
                Outcome.ALREADY_PRESENT,
                bootstrap("ollama", guard(Set.of("ollama:11434"))).bootstrapOnce());

        verify(service, never()).createAllowingBlankKey(any());
    }

    @Test
    void missingModelStillCreatesRowAndWarnsWithThePullCommand() {
        server.expect(requestTo(BASE + "/api/tags"))
                .andRespond(
                        withSuccess(
                                "{\"models\":[{\"name\":\"mistral:latest\"}]}",
                                MediaType.APPLICATION_JSON));
        PlatformHostedLlmProviderResponse made = created(2);
        when(service.createAllowingBlankKey(any())).thenReturn(made);

        Outcome outcome = bootstrap("ollama", guard(Set.of("ollama:11434"))).bootstrapOnce();

        assertEquals(Outcome.CREATED, outcome);
        assertTrue(logged(Level.WARN, "ollama pull llama3.2"));
        verify(service).createAllowingBlankKey(any());
    }

    @Test
    void fallsBackToOpenAiStyleModelListing() {
        server.expect(requestTo(BASE + "/api/tags")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/v1/models"))
                .andRespond(
                        withSuccess(
                                "{\"data\":[{\"id\":\"llama3.2\"}]}", MediaType.APPLICATION_JSON));

        assertEquals(
                Boolean.TRUE,
                bootstrap("ollama", guard(Set.of("ollama:11434"))).modelIsAvailable(BASE));
    }

    @Test
    void disallowedPrivateHostIsRefusedWithAnAllowedPrivateHostsHint() {
        Outcome outcome = bootstrap("ollama", guard(Set.of())).bootstrapOnce();

        assertEquals(Outcome.REFUSED, outcome);
        verify(service, never()).createAllowingBlankKey(any());
        assertTrue(logged(Level.WARN, "app.security.outbound.allowed-private-hosts"));
        assertTrue(logged(Level.WARN, "ollama:11434"));
    }

    @Test
    void neverThrowsOnUnexpectedErrors() {
        server.expect(requestTo(BASE + "/api/tags"))
                .andRespond(withSuccess(TAGS, MediaType.APPLICATION_JSON));
        when(service.createAllowingBlankKey(any())).thenThrow(new IllegalStateException("boom"));

        assertEquals(
                Outcome.FAILED, bootstrap("ollama", guard(Set.of("ollama:11434"))).bootstrapOnce());

        when(repository.findAllByOrderByPriorityAsc()).thenThrow(new RuntimeException("db down"));
        assertEquals(
                Outcome.FAILED, bootstrap("ollama", guard(Set.of("ollama:11434"))).bootstrapOnce());
    }

    @Test
    void unreachableServerIsRetriedThenGivenUpWithoutThrowing() {
        server.expect(ExpectedCount.times(6), requestTo(BASE + "/api/tags"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(ExpectedCount.times(6), requestTo(BASE + "/v1/models"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        when(service.createAllowingBlankKey(any()))
                .thenThrow(
                        new ProviderValidationException(
                                "Could not reach " + BASE + " to validate this key",
                                new RuntimeException("refused")));

        Outcome outcome = bootstrap("ollama", guard(Set.of("ollama:11434"))).runWithRetries();

        assertEquals(Outcome.UNREACHABLE, outcome);
        verify(service, times(6)).createAllowingBlankKey(any());
        assertTrue(logged(Level.WARN, "gave up"));
    }

    @Test
    void concurrentDuplicateKeepsTheLowerId() {
        server.expect(requestTo(BASE + "/api/tags"))
                .andRespond(withSuccess(TAGS, MediaType.APPLICATION_JSON));
        PlatformHostedLlmProviderResponse made = created(9);
        when(service.createAllowingBlankKey(any())).thenReturn(made);
        List<PlatformHostedLlmProvider> both =
                List.of(
                        row(4, "Local model (Ollama)", BASE, -1),
                        row(9, "Local model (Ollama)", BASE, -1));
        when(repository.findAllByOrderByPriorityAsc()).thenReturn(List.of()).thenReturn(both);

        assertEquals(
                Outcome.CREATED,
                bootstrap("ollama", guard(Set.of("ollama:11434"))).bootstrapOnce());

        verify(repository).deleteById(9L);
    }

    @Test
    void emptyBaseUrlOrModelRegistersNothing() {
        LocalLlmPoolBootstrap blank =
                new LocalLlmPoolBootstrap(
                        service, repository, builder, " ", "llama3.2", "x", "k", 0L);
        assertEquals(Outcome.INVALID_CONFIG, blank.bootstrapOnce());
        verify(service, never()).createAllowingBlankKey(any());
    }
}
