package com.agenticai.core.agents.tools.script;

import com.agenticai.core.agents.tools.script.dto.UserToolScriptRequest;
import org.springframework.web.bind.annotation.RequestParam;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.tools.script.dto.UserToolScriptResponse;
import com.agenticai.core.agents.tools.script.dto.UserToolScriptTestRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * #424 - CRUD + dry-run test for tenant-authored {@link UserToolScript} rows. Same auth posture as
 * {@code McpRegistryController} (not under {@code /api/admin/**}) - any authenticated tenant member
 * can define a custom tool, not just tenant-admins, matching how MCP server registration already
 * works.
 */
@RestController
@RequestMapping("/api/tools/scripts")
public class UserToolScriptController {

    private final UserToolScriptService service;

    public UserToolScriptController(UserToolScriptService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<UserToolScriptResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.listAll().stream().map(UserToolScriptResponse::from).toList(), limit, offset, 50, 200);
    }

    @PostMapping
    public ResponseEntity<UserToolScriptResponse> create(
            @Valid @RequestBody UserToolScriptRequest request) {
        UserToolScript created =
                service.create(
                        request.toolName(),
                        request.displayName(),
                        request.description(),
                        request.expression(),
                        request.inputSchemaJson());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserToolScriptResponse.from(created));
    }

    @PutMapping("/{id}")
    public UserToolScriptResponse update(
            @PathVariable UUID id, @Valid @RequestBody UserToolScriptRequest request) {
        UserToolScript updated =
                service.update(
                        id,
                        request.displayName(),
                        request.description(),
                        request.expression(),
                        request.inputSchemaJson());
        return UserToolScriptResponse.from(updated);
    }

    @PatchMapping("/{id}/enabled")
    public UserToolScriptResponse setEnabled(
            @PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return UserToolScriptResponse.from(service.setEnabled(id, enabled));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Dry-run - see {@code UserToolScriptService#test}. Returns 200 with a JSON error body on a bad
     * expression, not 4xx/5xx, since "the expression evaluated to an error" is a normal, expected
     * outcome of trying one out.
     */
    @PostMapping("/test")
    public Map<String, String> test(@Valid @RequestBody UserToolScriptTestRequest request) {
        return Map.of("resultJson", service.test(request.expression(), request.sampleInputJson()));
    }
}
