package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** V499 - the tool-call grade is measured on save and shapes the enabled chain. */
class PlatformHostedLlmProviderToolCallGradeTest {

    private PlatformHostedLlmProviderRepository repository;
    private ToolCallCapabilityProbe probe;
    private MockRestServiceServer mockServer;
    private PlatformHostedLlmProviderService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformHostedLlmProviderRepository.class);
        probe = mock(ToolCallCapabilityProbe.class);
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        service =
                new PlatformHostedLlmProviderService(
                        repository,
                        mock(HostedLlmPoolSettingsRepository.class),
                        mock(EncryptionService.class),
                        builder,
                        mock(ProviderModelCatalogService.class));
        service.setSsrfGuard(
                SsrfGuard.of(
                        Set.of(),
                        host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")}));
        service.setToolCallCapabilityProbe(probe);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        mockServer
                .expect(ExpectedCount.manyTimes(), requestTo("https://ollama.example/v1/models"))
                .andRespond(withSuccess());
    }

    private static PlatformHostedLlmProviderUpsertRequest request(String model) {
        return new PlatformHostedLlmProviderUpsertRequest(
                "Local", "https://ollama.example", model, "", true, 0, null, null, null);
    }

    private static ToolCallCapabilityProbe.Result result(ToolCallGrade grade) {
        return new ToolCallCapabilityProbe.Result(
                grade, ToolCallCapabilityProbe.ServerKind.OLLAMA, 3.2d);
    }

    @Test
    void createStoresTheMeasuredGrade() {
        when(probe.probe(eq("https://ollama.example"), isNull(), eq("llama3.2:3b"), any()))
                .thenReturn(Optional.of(result(ToolCallGrade.TEXT)));
        PlatformHostedLlmProviderResponse saved = service.createAllowingBlankKey(request("llama3.2:3b"));
        assertEquals("TEXT", saved.toolCallGrade());
    }

    @Test
    void createWithoutAnAnswerFromTheProbeLeavesTheGradeUnmeasuredAndStillSaves() {
        when(probe.probe(anyString(), any(), anyString(), any())).thenReturn(Optional.empty());
        assertNull(service.createAllowingBlankKey(request("llama3.2:3b")).toolCallGrade());
    }

    @Test
    void aProbeThatThrowsNeverFailsTheSave() {
        when(probe.probe(anyString(), any(), anyString(), any())).thenThrow(new IllegalStateException("boom"));
        assertNull(service.createAllowingBlankKey(request("llama3.2:3b")).toolCallGrade());
    }

    @Test
    void updateReMeasuresOnlyWhenTheModelOrEndpointChanged() {
        PlatformHostedLlmProvider row =
                new PlatformHostedLlmProvider(
                        "Local", "https://ollama.example", "llama3.2:3b", null, true, 0, null, null);
        row.setToolCallGrade(ToolCallGrade.NONE);
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(probe.probe(anyString(), any(), eq("qwen3.5:4b"), any()))
                .thenReturn(Optional.of(result(ToolCallGrade.STRUCTURED)));

        // Same model: a label/priority edit keeps the stored grade and costs no probe call.
        PlatformHostedLlmProviderResponse unchanged =
                service.update(
                        7L,
                        new PlatformHostedLlmProviderUpsertRequest(
                                "Renamed", "https://ollama.example", "llama3.2:3b", "", true, 5, null, null, null));
        assertEquals("NONE", unchanged.toolCallGrade());
        verify(probe, never()).probe(anyString(), any(), anyString(), any());

        PlatformHostedLlmProviderResponse changed = service.update(7L, request("qwen3.5:4b"));
        assertEquals("STRUCTURED", changed.toolCallGrade());
        verify(probe, times(1)).probe(anyString(), any(), eq("qwen3.5:4b"), any());
    }

    @Test
    void updateMeasuresAnUnmeasuredRowEvenWhenNothingChanged() {
        PlatformHostedLlmProvider row =
                new PlatformHostedLlmProvider(
                        "Local", "https://ollama.example", "llama3.2:3b", null, true, 0, null, null);
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(probe.probe(anyString(), any(), eq("llama3.2:3b"), any()))
                .thenReturn(Optional.of(result(ToolCallGrade.TEXT)));
        assertEquals("TEXT", service.update(7L, request("llama3.2:3b")).toolCallGrade());
    }

    @Test
    void chainSkipsChatOnlyRowsWhileAnotherRowCanCallTools() {
        PlatformHostedLlmProvider chatOnly = row("llama3.2:3b", ToolCallGrade.NONE);
        PlatformHostedLlmProvider text = row("qwen3.5:2b", ToolCallGrade.TEXT);
        PlatformHostedLlmProvider unmeasured = row("groq", null);
        List<PlatformHostedLlmProvider> kept =
                HostedPoolToolCallGrading.withoutChatOnlyRowsWhenAvoidable(
                        List.of(chatOnly, text, unmeasured));
        assertEquals(List.of(text, unmeasured), kept);
    }

    @Test
    void chainKeepsAChatOnlyRowWhenItIsTheOnlyOne() {
        List<PlatformHostedLlmProvider> only = List.of(row("llama3.2:3b", ToolCallGrade.NONE));
        assertSame(only, HostedPoolToolCallGrading.withoutChatOnlyRowsWhenAvoidable(only));
        List<PlatformHostedLlmProvider> twoChatOnly =
                List.of(row("a", ToolCallGrade.NONE), row("b", ToolCallGrade.NONE));
        assertSame(twoChatOnly, HostedPoolToolCallGrading.withoutChatOnlyRowsWhenAvoidable(twoChatOnly));
    }

    @Test
    void storedGradeRoundTripsAndUnknownTextReadsAsUnmeasured() {
        PlatformHostedLlmProvider row = row("m", ToolCallGrade.STRUCTURED);
        assertEquals(ToolCallGrade.STRUCTURED, row.getToolCallGrade());
        assertNull(ToolCallGrade.fromStored("SOMETHING_NEWER"));
        assertNull(ToolCallGrade.fromStored(" "));
        assertEquals(ToolCallGrade.TEXT, ToolCallGrade.fromStored("text"));
    }

    private static PlatformHostedLlmProvider row(String model, ToolCallGrade grade) {
        PlatformHostedLlmProvider row =
                new PlatformHostedLlmProvider(model, "https://x.example", model, null, true, 0, null, null);
        row.setToolCallGrade(grade);
        return row;
    }

    // --- V500: constrained tool router mode ---------------------------------------------

    @Test
    void createStoresTheServerKindAsRouterModeAndTheOverride() {
        when(probe.probe(anyString(), any(), eq("llama3.2:3b"), any()))
                .thenReturn(Optional.of(result(ToolCallGrade.TEXT)));
        PlatformHostedLlmProviderResponse saved = service.createAllowingBlankKey(request("llama3.2:3b"));
        assertEquals("FORMAT", saved.toolRouterMode());
        assertNull(saved.toolRouterEnabled());
        assertEquals("FORMAT", saved.toolRouterActive(), "TEXT on Ollama: router on by default");
    }

    @Test
    void routerIsOffForStructuredRowsUnlessForcedAndOffWhenDisabled() {
        PlatformHostedLlmProvider row = row("qwen3.5:4b", ToolCallGrade.STRUCTURED);
        row.setToolRouterMode(ToolRouterMode.FORMAT);
        assertNull(row.effectiveToolRouterMode(), "STRUCTURED: provider's own loop");
        row.setToolRouterEnabled(Boolean.TRUE);
        assertEquals(ToolRouterMode.FORMAT, row.effectiveToolRouterMode());
        row.setToolRouterMode(null);
        assertEquals(ToolRouterMode.FORMAT, row.effectiveToolRouterMode(), "forced on with no detected kind: FORMAT");

        PlatformHostedLlmProvider weak = row("llama3.2:3b", ToolCallGrade.NONE);
        weak.setToolRouterMode(ToolRouterMode.REQUIRED);
        assertEquals(ToolRouterMode.REQUIRED, weak.effectiveToolRouterMode());
        weak.setToolRouterEnabled(Boolean.FALSE);
        assertNull(weak.effectiveToolRouterMode());
        PlatformHostedLlmProvider unknownServer = row("groq-8b", ToolCallGrade.TEXT);
        assertNull(unknownServer.effectiveToolRouterMode(), "no detected mode: nothing to enforce");
        assertNull(row("unmeasured", null).effectiveToolRouterMode());
    }

    @Test
    void routerModeFollowsTheServerKind() {
        assertEquals(ToolRouterMode.FORMAT, ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.OLLAMA));
        assertEquals(ToolRouterMode.FORMAT, ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.LLAMA_CPP));
        assertEquals(ToolRouterMode.REQUIRED, ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.VLLM));
        assertNull(ToolRouterMode.forServer(ToolCallCapabilityProbe.ServerKind.OTHER));
        assertNull(ToolRouterMode.fromStored("later-mode"));
    }

    @Test
    void anEndpointChangeClearsTheOldMeasurementEvenWhenTheProbeCannotAnswer() {
        PlatformHostedLlmProvider row =
                new PlatformHostedLlmProvider(
                        "Local", "https://ollama.example", "llama3.2:3b", null, true, 0, null, null);
        row.setToolCallGrade(ToolCallGrade.NONE);
        row.setToolRouterMode(ToolRouterMode.FORMAT);
        when(repository.findById(7L)).thenReturn(Optional.of(row));
        when(probe.probe(anyString(), any(), anyString(), any())).thenReturn(Optional.empty());
        PlatformHostedLlmProviderResponse changed = service.update(7L, request("qwen3.5:4b"));
        assertNull(changed.toolCallGrade());
        assertNull(changed.toolRouterMode());
    }

    // --- V501: small-model mode ------------------------------------------------------------

    @Test
    void smallModelFollowsTheReportedSizeThenTheTagThenTheOverride() {
        PlatformHostedLlmProvider row = row("llama3.2:3b", ToolCallGrade.STRUCTURED);
        assertTrue(row.effectiveSmallModel(), "3b in the tag");
        row.setParameterBillions(3.4);
        assertEquals(3.4, row.effectiveParameterBillions(), "a reported size close to the tag wins");
        row.setParameterBillions(9.0);
        assertEquals(3.0, row.effectiveParameterBillions(), "a reported size far from the tag is a stale measurement");
        assertTrue(row.effectiveSmallModel(), "the tag wins over a measurement from another model");
        PlatformHostedLlmProvider untagged = row("gemma-small", null);
        untagged.setParameterBillions(9.0);
        assertFalse(untagged.effectiveSmallModel(), "with no size in the tag the reported size stands");
        row.setSmallModel(Boolean.TRUE);
        assertTrue(row.effectiveSmallModel(), "the override wins over everything");
        row.setSmallModel(Boolean.FALSE);
        row.setParameterBillions(3.2);
        assertFalse(row.effectiveSmallModel());

        assertEquals(8.0, PlatformHostedLlmProvider.parameterBillionsFromTag("llama-3.1-8b-instant"));
        assertEquals(4.0, PlatformHostedLlmProvider.parameterBillionsFromTag("qwen3.5:4b"));
        assertEquals(20.0, PlatformHostedLlmProvider.parameterBillionsFromTag("gpt-oss:20b"));
        assertNull(PlatformHostedLlmProvider.parameterBillionsFromTag("gemini-2.0-flash-lite"));
        assertNull(PlatformHostedLlmProvider.parameterBillionsFromTag("gpt-4o-2024-08-06"));
        assertFalse(row("gemini-2.0-flash-lite", null).effectiveSmallModel(), "unknown size is not small");
        assertFalse(row("gpt-oss:20b", null).effectiveSmallModel());
    }

    @Test
    void createStoresTheReportedSizeAndTheSmallModelOverride() {
        when(probe.probe(anyString(), any(), eq("llama3.2:3b"), any()))
                .thenReturn(Optional.of(result(ToolCallGrade.TEXT)));
        PlatformHostedLlmProviderResponse saved = service.createAllowingBlankKey(request("llama3.2:3b"));
        assertEquals(3.2d, saved.parameterBillions());
        assertNull(saved.smallModel());
        assertTrue(saved.smallModelActive());

        PlatformHostedLlmProviderResponse forcedOff =
                service.createAllowingBlankKey(
                        new PlatformHostedLlmProviderUpsertRequest(
                                "Local", "https://ollama.example", "llama3.2:3b", "", true, 0, null, null, null,
                                null, null, null, null, null, Boolean.FALSE));
        assertEquals(Boolean.FALSE, forcedOff.smallModel());
        assertFalse(forcedOff.smallModelActive());
    }
}
