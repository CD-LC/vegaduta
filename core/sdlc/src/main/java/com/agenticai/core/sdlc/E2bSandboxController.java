package com.agenticai.core.sdlc;

import com.agenticai.core.sdlc.dto.CreateSandboxRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.sdlc.dto.RunCodeRequest;
import com.agenticai.core.sdlc.dto.RunCodeResponse;
import com.agenticai.core.sdlc.dto.SandboxResponse;
import com.agenticai.core.sdlc.dto.SandboxSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual sandbox lifecycle control (create/list/kill) - the SDLC automation
 * agent itself does not exist yet in this scaffold pass; this controller
 * lets a tenant admin exercise and verify the E2B integration directly
 * before any agent tool is built on top of it. Every write is admin-gated
 * in {@code SecurityConfig} (same tier as any other capability that spends
 * a tenant's own BYOK-metered quota - see the E2B credential lines there);
 * {@link #list} is left readable by any authenticated tenant member, same
 * posture as every other channel/integration status read in this codebase.
 */
@RestController
@RequestMapping("/api/sdlc/sandbox")
public class E2bSandboxController {

    private final SdlcSandboxService sandboxService;

    public E2bSandboxController(SdlcSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @PostMapping
    public ResponseEntity<SandboxResponse> create(@Valid @RequestBody CreateSandboxRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(sandboxService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<SandboxSummaryResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(sandboxService.list(), limit, offset, 50, 200);
    }

    @DeleteMapping("/{sandboxId}")
    public ResponseEntity<Void> kill(@PathVariable String sandboxId) {
        sandboxService.kill(sandboxId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Master Roadmap 2026 item #14 (E2B code execution completion, §B) -
     * creates a fresh disposable sandbox from the tenant's configured
     * code-execution template, runs {@code code}, returns stdout/stderr/exit
     * code, then kills the sandbox. See {@link E2bCodeExecutionClient}'s
     * javadoc for why this goes through a tenant-owned template's own HTTP
     * contract rather than E2B's undocumented envd Process API.
     */
    @PostMapping("/run-code")
    public ResponseEntity<RunCodeResponse> runCode(@Valid @RequestBody RunCodeRequest request) {
        return ResponseEntity.ok(sandboxService.runCode(request));
    }
}
