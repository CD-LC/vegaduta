package com.agenticai.core.omnichannel.relay;

import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The browser side of the {@link EdgeRelayService edge relay}: register this device as the model
 * behind some channels, heartbeat, long-poll for the next message, hand the answer back. Every
 * route is tenant-scoped through the caller's token and gated to {@code PERM_MANAGE_CHANNELS} in
 * {@code ToolChannelWorkflowRules}. The console's /edge page drives it (web/app/lib/edge/relay.ts).
 */
@RestController
@RequestMapping("/api/channels/edge-relay")
public class EdgeRelayController {

    private final EdgeRelayService relay;

    public EdgeRelayController(EdgeRelayService relay) {
        this.relay = relay;
    }

    public record RegisterRequest(
            @NotEmpty Set<ChannelType> channels,
            @Size(max = 120) String modelLabel,
            @Size(max = 120) String deviceLabel) {}

    public record RegisterResponse(
            String relayId, long heartbeatSeconds, int claimWaitSecondsMax, long replyWaitSeconds) {}

    public record StatusResponse(boolean enabled, List<RelayStatus> relays) {}

    public record RelayStatus(
            String relayId,
            String subject,
            Set<ChannelType> channels,
            String modelLabel,
            String deviceLabel,
            long registeredAtEpochMs,
            long answered) {}

    public record ReplyRequest(@NotNull String relayId, @NotNull @Size(max = 20_000) String text) {}

    public record FailRequest(@NotNull String relayId, @Size(max = 500) String reason) {}

    @GetMapping("/status")
    public StatusResponse status() {
        String tenant = tenant();
        List<RelayStatus> relays =
                relay.onlineRelays(tenant).stream()
                        .map(
                                r ->
                                        new RelayStatus(
                                                r.relayId(),
                                                r.subject(),
                                                r.channels(),
                                                r.modelLabel(),
                                                r.deviceLabel(),
                                                r.registeredAtEpochMs(),
                                                relay.answered(tenant, r.relayId())))
                        .toList();
        return new StatusResponse(relay.isEnabled(), relays);
    }

    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        if (!relay.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The edge relay is disabled on this instance.");
        }
        EdgeRelayService.RelayInfo info =
                relay.register(tenant(), subject(), request.channels(), request.modelLabel(), request.deviceLabel());
        return new RegisterResponse(
                info.relayId(),
                // Heartbeat well inside the liveness TTL so one missed beat does not drop the relay.
                Math.max(5, relay.heartbeatTtl().toSeconds() / 2),
                relay.claimWaitSecondsMax(),
                relay.replyWait().toSeconds());
    }

    @PostMapping("/{relayId}/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(@PathVariable String relayId) {
        if (!relay.heartbeat(tenant(), relayId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown relay - register again.");
        }
    }

    @DeleteMapping("/{relayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@PathVariable String relayId) {
        relay.unregister(tenant(), relayId);
    }

    /** 200 with the next job, or 204 when nothing arrived within {@code wait} seconds. */
    @GetMapping("/{relayId}/next")
    public ResponseEntity<RelayJob> next(
            @PathVariable String relayId, @RequestParam(name = "wait", defaultValue = "20") int wait) {
        String tenant = tenant();
        if (relay.relay(tenant, relayId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown relay - register again.");
        }
        Optional<RelayJob> job = relay.claim(tenant, relayId, wait);
        return job.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/jobs/{jobId}/reply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reply(@PathVariable String jobId, @Valid @RequestBody ReplyRequest request) {
        if (!relay.complete(tenant(), request.relayId(), jobId, request.text())) {
            throw new ResponseStatusException(
                    HttpStatus.GONE, "This message was already answered on the hosted path or belongs to another relay.");
        }
    }

    @PostMapping("/jobs/{jobId}/fail")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fail(@PathVariable String jobId, @Valid @RequestBody FailRequest request) {
        relay.fail(tenant(), request.relayId(), jobId, request.reason());
    }

    private static String tenant() {
        return TenantContext.current()
                .map(TenantContext::getTenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant"));
    }

    private static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
    }
}
