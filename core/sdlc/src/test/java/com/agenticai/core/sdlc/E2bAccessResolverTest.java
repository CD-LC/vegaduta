package com.agenticai.core.sdlc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class E2bAccessResolverTest {

    private final E2bCredentialService creds = mock(E2bCredentialService.class);
    private final E2bPlatformAccessProvider platform = mock(E2bPlatformAccessProvider.class);

    @SuppressWarnings("unchecked")
    private E2bAccessResolver resolver(boolean platformEnabled, E2bPlatformAccessProvider provider) {
        ObjectProvider<E2bPlatformAccessProvider> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(provider);
        return new E2bAccessResolver(creds, p, platformEnabled);
    }

    private void tenant(boolean active, boolean template) {
        when(creds.isActive()).thenReturn(active);
        when(creds.hasCodeExecutionTemplate()).thenReturn(template);
        when(creds.resolveActiveApiKey()).thenReturn("tenant-key");
        when(creds.resolveCodeExecutionTemplateId()).thenReturn("tenant-tpl");
    }

    private void platformConfigured() {
        when(platform.isConfigured()).thenReturn(true);
        when(platform.resolve()).thenReturn(Optional.of(new E2bAccess("platform-key", "platform-tpl", E2bAccess.Source.PLATFORM)));
    }

    @Test
    void tenantCredentialWinsEvenWhenThePlatformFallbackIsConfigured() {
        tenant(true, true);
        platformConfigured();
        E2bAccessResolver resolver = resolver(true, platform);

        assertTrue(resolver.codeExecutionReady());
        E2bAccess access = resolver.resolveForCodeExecution();
        assertEquals(E2bAccess.Source.TENANT, access.source());
        assertEquals("tenant-key", access.apiKey());
        assertEquals("tenant-tpl", access.templateId());
        verify(platform, never()).resolve();
    }

    @Test
    void platformFallbackIsUsedWhenTheTenantHasNoUsableCredential() {
        tenant(false, false);
        platformConfigured();
        E2bAccessResolver resolver = resolver(true, platform);

        assertTrue(resolver.codeExecutionReady());
        E2bAccess access = resolver.resolveForCodeExecution();
        assertTrue(access.platformManaged());
        assertEquals("platform-key", access.apiKey());
        assertEquals("platform-tpl", access.templateId());
    }

    @Test
    void tenantKeyWithoutTemplateFallsBackToThePlatform() {
        tenant(true, false);
        platformConfigured();
        assertTrue(resolver(true, platform).resolveForCodeExecution().platformManaged());
    }

    @Test
    void flagOffNeverReadsThePlatformCredential() {
        tenant(false, false);
        when(creds.resolveActiveApiKey()).thenThrow(new E2bNotConfiguredException());
        platformConfigured();
        E2bAccessResolver resolver = resolver(false, platform);

        assertFalse(resolver.codeExecutionReady());
        assertFalse(resolver.platformFallbackEnabled());
        assertThrows(E2bNotConfiguredException.class, resolver::resolveForCodeExecution);
        verify(platform, never()).resolve();
        verify(platform, never()).isConfigured();
    }

    @Test
    void flagOnButPlatformUnconfiguredIsNotReadyAndThrowsNotConfigured() {
        tenant(false, false);
        when(platform.isConfigured()).thenReturn(false);
        when(platform.resolve()).thenReturn(Optional.empty());
        E2bAccessResolver resolver = resolver(true, platform);

        assertFalse(resolver.codeExecutionReady());
        E2bNotConfiguredException e = assertThrows(E2bNotConfiguredException.class, resolver::resolveForCodeExecution);
        assertTrue(e.getMessage().contains("not available yet"));
    }

    @Test
    void flagOnWithNoProviderBeanBehavesLikeUnconfigured() {
        tenant(false, false);
        E2bAccessResolver resolver = resolver(true, null);
        assertFalse(resolver.codeExecutionReady());
        assertThrows(E2bNotConfiguredException.class, resolver::resolveForCodeExecution);
    }

    @Test
    void aFailingPlatformProviderIsTreatedAsUnconfiguredNotAsAnError() {
        tenant(false, false);
        when(platform.isConfigured()).thenThrow(new IllegalStateException("db down"));
        when(platform.resolve()).thenThrow(new IllegalStateException("db down"));
        E2bAccessResolver resolver = resolver(true, platform);
        assertFalse(resolver.codeExecutionReady());
        assertThrows(E2bNotConfiguredException.class, resolver::resolveForCodeExecution);
    }

    @Test
    void aProviderThatMislabelsItsKeyIsStillMeteredAsPlatform() {
        tenant(false, false);
        when(platform.resolve()).thenReturn(Optional.of(new E2bAccess("k", "t", E2bAccess.Source.TENANT)));
        assertTrue(resolver(true, platform).resolveForCodeExecution().platformManaged());
    }

    @Test
    void accessToStringNeverLeaksTheKey() {
        assertFalse(new E2bAccess("secret-key", "tpl", E2bAccess.Source.PLATFORM).toString().contains("secret-key"));
    }

    /**
     * The REST surface and tenant custom-code tools call {@link SdlcSandboxService#runCode(RunCodeRequest)}:
     * that path resolves the TENANT template only and never takes an {@link E2bAccess}.
     */
    @Test
    void tenantOnlyRunCodePathNeverUsesAnExplicitAccess() {
        E2bSandboxClient sandboxClient = mock(E2bSandboxClient.class);
        E2bCodeExecutionClient codeClient = mock(E2bCodeExecutionClient.class);
        when(creds.resolveCodeExecutionTemplateId()).thenReturn("tenant-tpl");
        RunCodeResponse response = new RunCodeResponse("ok", "", 0, false, "sbx");
        when(codeClient.runCode(eq("tenant-tpl"), any(), any(), any(), any())).thenReturn(response);
        SdlcSandboxService service = new SdlcSandboxService(sandboxClient, codeClient, creds);

        assertEquals(response, service.runCode(new RunCodeRequest("print(1)", "python", 5)));
        verify(codeClient, never()).runCode(any(E2bAccess.class), any(), any(), any(), any());
    }

    @Test
    void tenantOnlyRunCodePathFailsWithoutATenantCredentialEvenIfAPlatformKeyExists() {
        E2bCodeExecutionClient codeClient = mock(E2bCodeExecutionClient.class);
        when(creds.resolveCodeExecutionTemplateId()).thenThrow(new E2bNotConfiguredException());
        SdlcSandboxService service = new SdlcSandboxService(mock(E2bSandboxClient.class), codeClient, creds);

        assertThrows(E2bNotConfiguredException.class,
            () -> service.runCode(new RunCodeRequest("print(1)", "python", 5)));
        verify(codeClient, never()).runCode(any(E2bAccess.class), any(), any(), any(), any());
    }

    @Test
    void accessRunCodeDelegatesWithTheGivenAccess() {
        E2bCodeExecutionClient codeClient = mock(E2bCodeExecutionClient.class);
        E2bAccess access = new E2bAccess("platform-key", "platform-tpl", E2bAccess.Source.PLATFORM);
        RunCodeResponse response = new RunCodeResponse("ok", "", 0, false, "sbx");
        when(codeClient.runCode(eq(access), eq("print(1)"), eq("python"), eq(30), any())).thenReturn(response);
        SdlcSandboxService service = new SdlcSandboxService(mock(E2bSandboxClient.class), codeClient, creds);

        assertEquals(response, service.runCode(access, new RunCodeRequest("print(1)", "python", 30)));
        verify(creds, never()).resolveCodeExecutionTemplateId();
    }
}
