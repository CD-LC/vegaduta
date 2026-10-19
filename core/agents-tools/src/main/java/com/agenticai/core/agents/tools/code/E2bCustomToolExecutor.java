package com.agenticai.core.agents.tools.code;

import com.agenticai.core.agents.tools.code.dto.SandboxExecuteResponse;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.sdlc.SdlcSandboxService;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * L6 first slice: runs a Python {@link CustomCodeTool} inside the tenant's own E2B sandbox
 * (Firecracker microVM, via {@link SdlcSandboxService#runCode}) instead of the tool-sandbox
 * service. Selected by {@code CustomCodeToolService} only when {@code
 * tool-sandbox.e2b-execution=true}; the tool-sandbox validator (AST allow-list) still gates which
 * tools may be saved, so this is a second isolation layer, not a replacement.
 *
 * <p>Deliberately narrow: Java tools (no E2B Java runner in the template) and tools that hold vault
 * secrets (never shipped into a third-party VM in this slice) are not {@link #supports supported}
 * and keep executing in tool-sandbox. When enabled but E2B is not configured the call fails closed
 * with {@link ToolSandboxUnavailableException}.
 *
 * <p>Wire contract with the tool program is the same as tool-sandbox: {@code def run(input)}
 * returning JSON-serialisable data. The program and its input travel base64-encoded inside a
 * generated harness so neither can break out of the harness source.
 */
@Component
public class E2bCustomToolExecutor {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    /** E2B boot overhead added on top of the tool's own wall clock. */
    static final int BOOT_BUDGET_SECONDS = 20;

    private final ObjectProvider<SdlcSandboxService> sandbox;

    public E2bCustomToolExecutor(ObjectProvider<SdlcSandboxService> sandbox) {
        this.sandbox = sandbox;
    }

    public boolean supports(CustomCodeToolLanguage language, Map<String, String> secrets) {
        return language == CustomCodeToolLanguage.PYTHON && (secrets == null || secrets.isEmpty());
    }

    public SandboxExecuteResponse execute(String source, JsonNode input, int timeoutMs) {
        SdlcSandboxService service = sandbox.getIfAvailable();
        if (service == null) {
            throw new ToolSandboxUnavailableException(
                    "The E2B code sandbox is not available on this deployment.");
        }
        String nonce = "__VEGA_RESULT_" + UUID.randomUUID().toString().replace("-", "") + "__";
        String inputJson = input == null ? "{}" : input.toString();
        int seconds = (int) Math.ceil(timeoutMs / 1000.0) + BOOT_BUDGET_SECONDS;
        long started = System.nanoTime();
        RunCodeResponse run;
        try {
            run =
                    service.runCode(
                            new RunCodeRequest(
                                    buildProgram(source, inputJson, nonce), "python", seconds));
        } catch (RuntimeException e) {
            throw new ToolSandboxUnavailableException(
                    "The E2B code sandbox could not run this tool: " + e.getMessage(), e);
        }
        if (run == null) {
            throw new ToolSandboxUnavailableException(
                    "The E2B code sandbox returned an empty response.");
        }
        return parse(run, nonce, (int) ((System.nanoTime() - started) / 1_000_000L));
    }

    static String buildProgram(String source, String inputJson, String nonce) {
        String src = b64(source == null ? "" : source);
        String in = b64(inputJson);
        return "import base64, json, sys\n"
                + "_ns = {'__name__': '__vega_tool__'}\n"
                + "exec(compile(base64.b64decode('"
                + src
                + "').decode('utf-8'), '<tool>', 'exec'), _ns)\n"
                + "_run = _ns.get('run')\n"
                + "if not callable(_run):\n"
                + "    sys.stderr.write('The program must define run(input).\\n')\n"
                + "    sys.exit(3)\n"
                + "_out = _run(json.loads(base64.b64decode('"
                + in
                + "').decode('utf-8')))\n"
                + "sys.stdout.write('\\n"
                + nonce
                + "' + json.dumps(_out) + '\\n')\n";
    }

    static SandboxExecuteResponse parse(RunCodeResponse run, String nonce, int durationMs) {
        String stdout = run.stdout() == null ? "" : run.stdout();
        String stderr = run.stderr() == null ? "" : run.stderr();
        if (run.timedOut()) {
            return fail(
                    "TIMEOUT", "The program exceeded its time limit.", stdout, stderr, durationMs);
        }
        if (run.exitCode() != null && run.exitCode() != 0) {
            String type = run.exitCode() == 3 ? "VALIDATION" : "RUNTIME";
            return fail(
                    type,
                    lastLine(stderr, "The program failed (exit " + run.exitCode() + ")."),
                    stdout,
                    stderr,
                    durationMs);
        }
        int idx = stdout.lastIndexOf(nonce);
        if (idx < 0) {
            return fail("INTERNAL", "The program produced no result.", stdout, stderr, durationMs);
        }
        String json = stdout.substring(idx + nonce.length()).strip();
        String visible = stdout.substring(0, idx).strip();
        try {
            JsonNode result = MAPPER.readTree(json);
            return new SandboxExecuteResponse(
                    true, result, visible, stderr, null, null, durationMs, false);
        } catch (Exception e) {
            return fail(
                    "INTERNAL",
                    "The program result was not valid JSON.",
                    visible,
                    stderr,
                    durationMs);
        }
    }

    private static SandboxExecuteResponse fail(
            String type, String message, String stdout, String stderr, int durationMs) {
        return new SandboxExecuteResponse(
                false, null, stdout, stderr, type, message, durationMs, false);
    }

    private static String lastLine(String text, String fallback) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return fallback;
        }
        int nl = t.lastIndexOf('\n');
        return nl < 0 ? t : t.substring(nl + 1).strip();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
