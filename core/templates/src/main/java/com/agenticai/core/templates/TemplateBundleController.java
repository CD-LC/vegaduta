package com.agenticai.core.templates;

import com.agenticai.core.templates.dto.AgentBundle;
import com.agenticai.core.templates.dto.AgentBundlePreview;
import com.agenticai.core.templates.dto.TemplateImportResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shareable agent files: export one of my agents, preview a bundle, import it. Authenticated. */
@RestController
@RequestMapping("/api/agent-bundles")
public class TemplateBundleController {

    private final TemplateBundleService service;

    public TemplateBundleController(TemplateBundleService service) {
        this.service = service;
    }

    /** One of my agents as a bundle file. */
    @GetMapping("/{agentId}")
    public AgentBundle export(@PathVariable UUID agentId) {
        return service.export(agentId);
    }

    /** What importing this bundle would do. Creates nothing. */
    @PostMapping("/preview")
    public AgentBundlePreview preview(@Valid @RequestBody AgentBundle bundle) {
        return service.preview(bundle);
    }

    /** Create a new agent in my workspace from a bundle. */
    @PostMapping("/import")
    public ResponseEntity<TemplateImportResponse> importBundle(
            @Valid @RequestBody AgentBundle bundle) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.importBundle(bundle));
    }
}
