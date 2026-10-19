package com.agenticai.core.agents.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.code.dto.SandboxExecuteRequest;
import com.agenticai.core.agents.tools.code.dto.SandboxExecuteResponse;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.sdlc.SdlcSandboxService;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.secrets.SecretRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class E2bCustomToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NONCE = "__VEGA_RESULT_abc__";

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @Test
    void supportsOnlyPythonWithoutSecrets() {
        E2bCustomToolExecutor e = new E2bCustomToolExecutor(providerOf(null));
        assertThat(e.supports(CustomCodeToolLanguage.PYTHON, Map.of())).isTrue();
        assertThat(e.supports(CustomCodeToolLanguage.JAVA, Map.of())).isFalse();
        assertThat(e.supports(CustomCodeToolLanguage.PYTHON, Map.of("K", "v"))).isFalse();
    }

    @Test
    void harnessEmbedsSourceAndInputOnlyAsBase64() {
        String program =
                E2bCustomToolExecutor.buildProgram(
                        "def run(i):\n  return '\"; evil()'", "{\"a\":1}", NONCE);
        assertThat(program)
                .doesNotContain("evil()")
                .contains(NONCE)
                .doesNotContain("def run(i)")
                .isNotNull();
        assertThat(program).contains("exec(compile(base64.b64decode(");
    }

    @Test
    void parsesResultAndStripsMarkerFromStdout() throws Exception {
        RunCodeResponse run =
                new RunCodeResponse("hello\n\n" + NONCE + "{\"x\":2}\n", "", 0, false, "sb");
        SandboxExecuteResponse r = E2bCustomToolExecutor.parse(run, NONCE, 5);
        assertThat(r.ok()).isTrue();
        assertThat(r.result()).isEqualTo(MAPPER.readTree("{\"x\":2}"));
        assertThat(r.stdout()).isEqualTo("hello");
    }

    @Test
    void mapsTimeoutAndRuntimeFailures() {
        SandboxExecuteResponse t =
                E2bCustomToolExecutor.parse(
                        new RunCodeResponse("", "", null, true, "sb"), NONCE, 5);
        assertThat(t.ok()).isFalse();
        assertThat(t.errorType()).isEqualTo("TIMEOUT");

        SandboxExecuteResponse f =
                E2bCustomToolExecutor.parse(
                        new RunCodeResponse("", "Traceback\nValueError: nope", 1, false, "sb"),
                        NONCE,
                        5);
        assertThat(f.errorType()).isEqualTo("RUNTIME");
        assertThat(f.error()).isEqualTo("ValueError: nope");

        SandboxExecuteResponse v =
                E2bCustomToolExecutor.parse(new RunCodeResponse("", "", 3, false, "sb"), NONCE, 5);
        assertThat(v.errorType()).isEqualTo("VALIDATION");
    }

    @Test
    void missingResultMarkerIsInternalError() {
        SandboxExecuteResponse r =
                E2bCustomToolExecutor.parse(
                        new RunCodeResponse("noise", "", 0, false, "sb"), NONCE, 5);
        assertThat(r.ok()).isFalse();
        assertThat(r.errorType()).isEqualTo("INTERNAL");
    }

    @Test
    void failsClosedWhenSandboxServiceMissingOrThrows() {
        assertThatThrownBy(
                        () -> new E2bCustomToolExecutor(providerOf(null)).execute("x", null, 1000))
                .isInstanceOf(ToolSandboxUnavailableException.class);
        SdlcSandboxService svc = mock(SdlcSandboxService.class);
        when(svc.runCode(any())).thenThrow(new IllegalStateException("no template"));
        assertThatThrownBy(
                        () -> new E2bCustomToolExecutor(providerOf(svc)).execute("x", null, 1000))
                .isInstanceOf(ToolSandboxUnavailableException.class)
                .hasMessageContaining("no template");
    }

    @Test
    void timeoutBudgetIncludesBootOverhead() {
        SdlcSandboxService svc = mock(SdlcSandboxService.class);
        when(svc.runCode(any())).thenReturn(new RunCodeResponse("", "", 0, false, "sb"));
        new E2bCustomToolExecutor(providerOf(svc)).execute("x", null, 5000);
        ArgumentCaptor<RunCodeRequest> cap = ArgumentCaptor.forClass(RunCodeRequest.class);
        verify(svc).runCode(cap.capture());
        assertThat(cap.getValue().timeoutSeconds())
                .isEqualTo(5 + E2bCustomToolExecutor.BOOT_BUDGET_SECONDS);
        assertThat(cap.getValue().language()).isEqualTo("python");
    }

    @Test
    void serviceRoutesToE2bOnlyWhenFlagOn() {
        ToolSandboxClient client = mock(ToolSandboxClient.class);
        when(client.execute(any(SandboxExecuteRequest.class)))
                .thenReturn(new SandboxExecuteResponse(true, null, "", "", null, null, 1, false));
        E2bCustomToolExecutor e2b = mock(E2bCustomToolExecutor.class);
        when(e2b.supports(any(), any())).thenReturn(true);
        when(e2b.execute(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new SandboxExecuteResponse(true, null, "", "", null, null, 1, false));
        ToolSandboxProperties props = new ToolSandboxProperties();
        CustomCodeToolService svc =
                new CustomCodeToolService(
                        mock(CustomCodeToolRepository.class),
                        props,
                        providerOf(client),
                        providerOf(mock(AuditLogService.class)),
                        providerOf(null),
                        providerOf(new SecretRedactor()));
        svc.setE2bExecutor(e2b);
        CustomCodeTool tool =
                new CustomCodeTool(
                        "my_tool",
                        "Display",
                        "A tool.",
                        CustomCodeToolLanguage.PYTHON,
                        "def run(i): return 1",
                        null,
                        null,
                        "deadbeef",
                        5000,
                        128,
                        "tester");

        svc.execute(tool, "{}");
        verify(client).execute(any(SandboxExecuteRequest.class));
        verify(e2b, never()).execute(any(), any(), org.mockito.ArgumentMatchers.anyInt());

        props.setE2bExecution(true);
        svc.execute(tool, "{}");
        verify(e2b).execute(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
