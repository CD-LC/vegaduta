package io.vegaduta.agent.autoconfigure;

import io.vegaduta.agent.api.approval.ApprovalAlreadyDecidedException;
import io.vegaduta.agent.api.approval.ApprovalGate;
import io.vegaduta.agent.api.approval.ApprovalNotAuthorizedException;
import io.vegaduta.agent.api.approval.ApprovalNotFoundException;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal REST surface for approvals, enabled with {@code
 * vegaduta.agent.approval.endpoint.enabled=true}. It carries no authentication of its own: protect
 * the path with Spring Security (or your gateway) and pass the approver's identity as {@code
 * decidedBy}.
 *
 * <pre>
 * GET  {path}?tenantId=&amp;limit=            pending requests
 * GET  {path}/{id}                           one request
 * POST {path}/{id}/approve  {"decidedBy":"bob"}
 * POST {path}/{id}/reject   {"decidedBy":"bob","reason":"..."}
 * </pre>
 */
@RestController
@RequestMapping("${vegaduta.agent.approval.endpoint.path:/vegaduta/approvals}")
public class ApprovalEndpoint {

  private final ApprovalGate approvals;

  public ApprovalEndpoint(ApprovalGate approvals) {
    this.approvals = approvals;
  }

  @GetMapping
  public List<Map<String, Object>> pending(
      @RequestParam(required = false) @Nullable String tenantId,
      @RequestParam(defaultValue = "50") int limit) {
    return approvals.pending(tenantId, limit).stream().map(ApprovalEndpoint::view).toList();
  }

  @GetMapping("/{id}")
  public ResponseEntity<Map<String, Object>> one(@PathVariable UUID id) {
    return approvals
        .find(id)
        .map(r -> ResponseEntity.ok(view(r)))
        .orElse(ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/approve")
  public Map<String, Object> approve(@PathVariable UUID id, @RequestBody Decision body) {
    return view(approvals.approve(id, body.decidedBy()));
  }

  @PostMapping("/{id}/reject")
  public Map<String, Object> reject(@PathVariable UUID id, @RequestBody Decision body) {
    return view(approvals.reject(id, body.decidedBy(), body.reason()));
  }

  public record Decision(String decidedBy, @Nullable String reason) {}

  @ExceptionHandler(ApprovalNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public Map<String, String> notFound(ApprovalNotFoundException e) {
    return Map.of("error", e.getMessage());
  }

  @ExceptionHandler(ApprovalAlreadyDecidedException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public Map<String, String> conflict(ApprovalAlreadyDecidedException e) {
    return Map.of("error", e.getMessage());
  }

  @ExceptionHandler(ApprovalNotAuthorizedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public Map<String, String> forbidden(ApprovalNotAuthorizedException e) {
    return Map.of("error", e.getMessage());
  }

  static Map<String, Object> view(ApprovalRequest r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id().toString());
    m.put("actionType", r.actionType());
    m.put("summary", r.summary());
    m.put("payload", r.payloadJson());
    m.put("status", r.status().name());
    m.put("requestedBy", r.requestedBy());
    m.put("tenantId", r.context().tenantId());
    m.put("agentId", r.context().agentId());
    m.put("runId", r.context().runId());
    m.put("createdAt", r.createdAt().toString());
    m.put("expiresAt", r.expiresAt().toString());
    m.put("decidedAt", r.decidedAt() == null ? null : r.decidedAt().toString());
    m.put("decidedBy", r.decidedBy());
    m.put("decisionReason", r.decisionReason());
    m.put("result", r.resultJson());
    return m;
  }
}
