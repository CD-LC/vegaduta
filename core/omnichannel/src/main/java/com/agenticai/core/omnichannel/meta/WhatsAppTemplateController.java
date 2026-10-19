package com.agenticai.core.omnichannel.meta;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lists the tenant's APPROVED WhatsApp message templates so the workflow Output node can offer
 * them in a picker. Read-only and fetched live from Meta on every call - nothing is stored.
 *
 * <p>Tenant comes from the request's own context (the credential lookup is tenant-scoped); the
 * caller must hold {@code PERM_MANAGE_CHANNELS} - the granular permission every other channel
 * admin surface uses, which the tenant-admin role seeds but does not bypass. Checked here rather
 * than in {@code SecurityConfig}, same shape as {@code OperatorInboxController}. Behind {@code app.omnichannel.whatsapp.templates.enabled}: off
 * (the default) answers 404, exactly as if the route did not exist.
 */
@RestController
@RequestMapping("/api/channels/whatsapp/templates")
public class WhatsAppTemplateController {

    private static final String CHANNELS_AUTHORITY = "PERM_MANAGE_CHANNELS";
    static final int PREVIEW_CHARS = 300;

    private final WhatsAppDispatchService dispatchService;
    private final boolean enabled;

    public WhatsAppTemplateController(
        WhatsAppDispatchService dispatchService,
        @Value("${app.omnichannel.whatsapp.templates.enabled:false}") boolean enabled
    ) {
        this.dispatchService = dispatchService;
        this.enabled = enabled;
    }

    /** {@code preview} is the template's body text with its {{n}} placeholders, capped. */
    public record WhatsAppTemplateResponse(String name, String language, int bodyParamCount, String preview, String category) {
    }

    /**
     * 200 with a (possibly empty) list - empty when Meta is not this tenant's connected WhatsApp
     * provider. 404 while the feature flag is off, 403 without the authority; a Meta-side failure
     * (e.g. the token lacks whatsapp_business_management) surfaces through the existing
     * {@code ChannelConfigurationException} mapping.
     */
    @GetMapping
    public ResponseEntity<List<WhatsAppTemplateResponse>> list(Authentication authentication, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "WhatsApp templates are not enabled.");
        }
        if (!mayManageChannels(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Listing WhatsApp templates needs the Manage channels permission.");
        }
        return ListPaging.window(
            dispatchService.listApprovedTemplates().stream()
            .map(t -> new WhatsAppTemplateResponse(
                t.name(), t.language(), t.bodyParamCount(), preview(t.bodyText()), t.category()))
            .toList(),
            limit, offset, 50, 500);
    }

    private static boolean mayManageChannels(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> CHANNELS_AUTHORITY.equals(authority.getAuthority()));
    }

    private static String preview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= PREVIEW_CHARS ? body : body.substring(0, PREVIEW_CHARS) + "...";
    }
}
