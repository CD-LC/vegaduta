package com.agenticai.core.omnichannel.whatsapp.policy;

import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Opt-in records and the phone number quality status for the Channels page. */
@RestController
@RequestMapping("/api/channels/whatsapp-compliance")
public class WhatsAppPolicyController {

    private final WhatsAppSendGuard guard;
    private final WhatsAppDispatchService dispatch;

    public WhatsAppPolicyController(WhatsAppSendGuard guard, WhatsAppDispatchService dispatch) {
        this.guard = guard;
        this.dispatch = dispatch;
    }

    public record OptInRequest(
            @NotBlank String phone, @NotBlank String source, String evidence) {}

    public record OptInResponse(String phoneDigits, String source, boolean active) {}

    @PostMapping("/opt-in")
    public OptInResponse recordOptIn(@Valid @RequestBody OptInRequest request) {
        String by = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        WhatsAppSendGuard.OptInView row =
                guard.recordOptIn(request.phone(), request.source(), request.evidence(), by);
        return new OptInResponse(row.phoneDigits(), row.source(), row.active());
    }

    /** One row of the opt-in list: same phone/source/active fields as {@link OptInResponse} plus consent timing. */
    public record OptInListItemResponse(
            String phoneDigits, String source, boolean active, Instant consentedAt, Instant revokedAt) {}

    /**
     * This tenant's opt-in records, newest consent first. {@code limit} 1..500 (default 50),
     * {@code offset} skips the newest rows, {@code activeOnly=true} hides revoked opt-ins.
     * Carries {@code X-Total-Count} / {@code X-Has-More}. Phone numbers appear exactly as
     * {@code POST /opt-in} returns them (digits only); evidence and the recording user are never
     * included. Rows come from the tenant's own schema.
     */
    @GetMapping("/opt-in")
    public ResponseEntity<List<OptInListItemResponse>> listOptIns(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset,
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        int size = Math.min(Math.max(limit == null ? 50 : limit, 1), 500);
        long skip = ListPaging.offset(offset);
        List<OptInListItemResponse> rows =
                guard.listOptIns(activeOnly, skip, size).stream()
                        .map(
                                r ->
                                        new OptInListItemResponse(
                                                r.phoneDigits(),
                                                r.source(),
                                                r.active(),
                                                r.consentedAt(),
                                                r.revokedAt()))
                        .toList();
        return ListPaging.respond(rows, size, skip, () -> guard.countOptIns(activeOnly));
    }

    @DeleteMapping("/opt-in")
    public ResponseEntity<Void> revokeOptIn(@RequestParam String phone) {
        return guard.revokeOptIn(phone)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** {@code available=false} when the connected provider exposes no rating (or none is connected). */
    public record QualityResponse(boolean available, String qualityRating, String status) {}

    @GetMapping("/quality")
    public QualityResponse quality() {
        Optional<WhatsAppProvider.PhoneHealth> health =
                dispatch.activeProvider().flatMap(WhatsAppProvider::phoneHealth);
        return health.map(h -> new QualityResponse(true, h.qualityRating(), h.status()))
                .orElse(new QualityResponse(false, null, null));
    }

    @GetMapping("/tasks")
    public String[] tasks() {
        return Arrays.stream(WhatsAppTask.values()).map(Enum::name).toArray(String[]::new);
    }
}
