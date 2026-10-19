package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.providers.ToolCallCapabilityProbe.Result;
import com.agenticai.core.providers.ToolCallCapabilityProbe.ServerKind;
import com.agenticai.core.providers.validation.ProviderValidationException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class LocalOpenAiProbeServiceTest {

    private LlmProviderCredentialRepository credentials;
    private EncryptionService encryption;
    private LocalOpenAiServerClient serverClient;
    private ToolCallCapabilityProbe probe;
    private LocalOpenAiProbeService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        credentials = mock(LlmProviderCredentialRepository.class);
        encryption = mock(EncryptionService.class);
        serverClient = mock(LocalOpenAiServerClient.class);
        probe = mock(ToolCallCapabilityProbe.class);
        ObjectProvider<ToolCallCapabilityProbe> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(probe);
        service = new LocalOpenAiProbeService(credentials, encryption, serverClient, provider);
    }

    private void connect(String baseUrl, String key) {
        when(credentials.findByProviderName(ProviderName.LOCAL_OPENAI)).thenReturn(Optional.of(
            new LlmProviderCredential(ProviderName.LOCAL_OPENAI, "enc", ProviderStatus.ACTIVE)));
        when(encryption.decrypt("enc")).thenReturn(new LocalOpenAiCredential(baseUrl, key).encode());
    }

    @Test
    void probesTheTenantsServerThroughTheGuardedPathWithTheNormalisedRoot() {
        connect("http://vllm.lan:8000/v1", "sk-1");
        when(probe.probeTenantServer("http://vllm.lan:8000", null, "qwen3-8b", "sk-1"))
            .thenReturn(Optional.of(new Result(ToolCallGrade.STRUCTURED, ServerKind.VLLM, null)));

        LocalOpenAiProbeResponse response = service.probe("qwen3-8b");

        assertThat(response.toolCallGrade()).isEqualTo(ToolCallGrade.STRUCTURED);
        assertThat(response.serverKind()).isEqualTo(ServerKind.VLLM);
        assertThat(response.toolRouterMode()).isEqualTo(ToolRouterMode.REQUIRED);
        assertThat(response.advice()).contains("Native tool calling works");
        verify(probe, never()).probe(any(), any(), any(), any());
    }

    @Test
    void aKeylessServerIsProbedWithoutAKey() {
        connect("http://localhost:8080", null);
        when(probe.probeTenantServer(any(), any(), any(), eq(null)))
            .thenReturn(Optional.of(new Result(ToolCallGrade.NONE, ServerKind.LLAMA_CPP, null)));

        LocalOpenAiProbeResponse response = service.probe("m");

        assertThat(response.toolCallGrade()).isEqualTo(ToolCallGrade.NONE);
        assertThat(response.toolRouterMode()).isEqualTo(ToolRouterMode.FORMAT);
        assertThat(response.advice()).contains("--jinja");
    }

    @Test
    void textGradeExplainsTheRecovery() {
        connect("http://h:1", null);
        when(probe.probeTenantServer(any(), any(), any(), any()))
            .thenReturn(Optional.of(new Result(ToolCallGrade.TEXT, ServerKind.OTHER, 3.2)));

        LocalOpenAiProbeResponse response = service.probe("m");

        assertThat(response.parameterBillions()).isEqualTo(3.2);
        assertThat(response.toolRouterMode()).isNull();
        assertThat(response.advice()).contains("plain text").contains("tool-trained model");
    }

    @Test
    void anUnmeasurableServerIsReportedAsSuchNotAsAnError() {
        connect("http://h:1", null);
        when(probe.probeTenantServer(any(), any(), any(), any())).thenReturn(Optional.empty());

        LocalOpenAiProbeResponse response = service.probe("m");

        assertThat(response.toolCallGrade()).isNull();
        assertThat(response.advice()).contains("could not be measured");
    }

    @Test
    void aProbeCrashNeverPropagates() {
        connect("http://h:1", null);
        when(probe.probeTenantServer(any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));

        assertThat(service.probe("m").toolCallGrade()).isNull();
    }

    @Test
    void theSsrfGuardRunsBeforeAnyProbeRequest() {
        connect("http://169.254.169.254", null);
        doThrow(new ProviderValidationException("This base URL is not allowed")).when(serverClient).requireAllowed(any());

        assertThatThrownBy(() -> service.probe("m")).isInstanceOf(ProviderValidationException.class);
        verify(probe, never()).probeTenantServer(any(), any(), any(), any());
    }

    @Test
    void requiresAnActiveConnection() {
        when(credentials.findByProviderName(ProviderName.LOCAL_OPENAI)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.probe("m")).isInstanceOf(ProviderNotActiveException.class);
        verify(probe, never()).probeTenantServer(any(), any(), any(), any());
    }

    @Test
    void aDeactivatedConnectionIsNotProbed() {
        LlmProviderCredential inactive = new LlmProviderCredential(ProviderName.LOCAL_OPENAI, "enc", ProviderStatus.ACTIVE);
        inactive.deactivate();
        when(credentials.findByProviderName(ProviderName.LOCAL_OPENAI)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.probe("m")).isInstanceOf(ProviderNotActiveException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAProbeBeanTheAnswerIsNotMeasured() {
        ObjectProvider<ToolCallCapabilityProbe> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        LocalOpenAiProbeService bare = new LocalOpenAiProbeService(credentials, encryption, serverClient, none);
        connect("http://h:1", null);

        assertThat(bare.probe("m").toolCallGrade()).isNull();
    }
}
