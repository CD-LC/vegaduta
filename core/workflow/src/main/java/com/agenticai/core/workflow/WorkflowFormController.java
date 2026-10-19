package com.agenticai.core.workflow;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/public/workflow-forms/{token}} - the PUBLIC half of a hosted-form trigger. No auth of any kind:
 * possession of the opaque per-node token is the whole credential (the webhook trigger's posture; see {@link
 * WorkflowFormService}). Lives under {@code /api/**} so the browser form page on the web origin passes CORS; only
 * the {@code GET} and {@code POST} on exactly this path are {@code permitAll} in SecurityConfig. Thin: every
 * decision (flag, limits, validation) is the service's. Unknown/disabled/inactive all answer the same 404.
 */
@RestController
@RequestMapping("/api/public/workflow-forms")
public class WorkflowFormController {

    private final WorkflowFormService formService;

    public WorkflowFormController(WorkflowFormService formService) {
        this.formService = formService;
    }

    /** The form definition the page renders. */
    @GetMapping("/{token}")
    public ResponseEntity<?> view(@PathVariable String token, HttpServletRequest request) {
        if (!formService.allowView(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
        }
        Optional<WorkflowFormService.FormView> view = formService.view(token);
        if (view.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        return ResponseEntity.ok(view.get());
    }

    /** One submission: {@code {"values": {key: value}, "_hp": ""}}. */
    @PostMapping("/{token}")
    public ResponseEntity<?> submit(
        @PathVariable String token, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request
    ) {
        WorkflowFormService.Submission result = formService.submit(token, body, request.getRemoteAddr());
        return switch (result.status()) {
            case OK -> {
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("ok", true);
                ok.put("message", result.message());
                ok.put("redirectUrl", result.redirectUrl());
                yield ResponseEntity.ok(ok);
            }
            case INVALID -> ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("ok", false, "errors", result.errors()));
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
            case TOO_LARGE -> ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "payload_too_large"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        };
    }
}
