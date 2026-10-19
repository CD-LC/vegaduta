package com.agenticai.core.sdlc;

import com.agenticai.core.sdlc.dto.CreateSandboxRequest;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.sdlc.dto.SandboxResponse;
import com.agenticai.core.sdlc.dto.SandboxSummaryResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Thin orchestration layer over {@link E2bSandboxClient} for the REST
 * surface ({@link E2bSandboxController}) and, in a later stage, for an
 * agent-facing tool callback (not built in this scaffold pass - see the
 * module's pom.xml description for the staged plan). Deliberately does not
 * persist a "which sandboxes did this tenant create" table yet - E2B itself
 * is the source of truth for what's running (via {@link
 * E2bSandboxClient#listSandboxes}), and adding a shadow-copy table before
 * there's a real caller of it (an SDLC agent) would be a table with no
 * proven read pattern, the kind of premature persistence this codebase's
 * "build the real caller first" convention avoids elsewhere.
 */
@Service
public class SdlcSandboxService {

    private final E2bSandboxClient sandboxClient;
    private final E2bCodeExecutionClient codeExecutionClient;
    private final E2bCredentialService credentialService;

    public SdlcSandboxService(
        E2bSandboxClient sandboxClient,
        E2bCodeExecutionClient codeExecutionClient,
        E2bCredentialService credentialService
    ) {
        this.sandboxClient = sandboxClient;
        this.codeExecutionClient = codeExecutionClient;
        this.credentialService = credentialService;
    }

    public SandboxResponse create(CreateSandboxRequest request) {
        E2bSandboxClient.SandboxHandle handle = sandboxClient.createSandbox(
            request.templateId(), request.timeoutSeconds(), request.envVars(), request.metadata()
        );
        return SandboxResponse.from(handle);
    }

    public void kill(String sandboxId) {
        sandboxClient.killSandbox(sandboxId);
    }

    public List<SandboxSummaryResponse> list() {
        return sandboxClient.listSandboxes().stream().map(SandboxSummaryResponse::from).toList();
    }

    /** Master Roadmap 2026 item #14 - resolves the tenant's configured code-execution template ID, then delegates to {@link E2bCodeExecutionClient}. Throws {@link E2bNotConfiguredException} (mapped to 400 by GlobalExceptionHandler) if no template is configured yet. */
    public RunCodeResponse runCode(RunCodeRequest request) {
        String templateId = credentialService.resolveCodeExecutionTemplateId();
        return codeExecutionClient.runCode(templateId, request.code(), request.language(), request.timeoutSeconds(), request.files());
    }

    /**
     * {@code run_code} only: runs on an already-resolved {@link E2bAccess} (tenant key or the
     * platform-managed fallback - see {@link E2bAccessResolver}). The REST surface and custom-code
     * tools keep using {@link #runCode(RunCodeRequest)}, which is tenant-key only.
     */
    public RunCodeResponse runCode(E2bAccess access, RunCodeRequest request) {
        return codeExecutionClient.runCode(access, request.code(), request.language(), request.timeoutSeconds(), request.files());
    }
}
