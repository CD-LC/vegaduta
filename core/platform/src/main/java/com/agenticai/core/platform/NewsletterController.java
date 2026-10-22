package com.agenticai.core.platform;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin "Newsletter / news broadcast" feature -
 * {@code /api/platform/newsletter[/recipients]}. Gated by the same {@code
 * SecurityConfig} {@code .requestMatchers("/api/platform/**")
 * .hasAuthority("ROLE_platform-admin")} rule as every other controller under
 * this path prefix (that matcher is a wildcard over the whole path prefix,
 * not tied to a specific controller class - see {@code PlatformController}'s
 * own javadoc for the same posture) - no separate authorization check
 * inside this class itself.
 *
 * <p>Kept as its own small controller rather than a new method on the
 * existing {@code PlatformController} - that class is long-lived and under
 * active concurrent edits by other work in this repo; a self-contained
 * feature with its own single-purpose service ({@link NewsletterService})
 * has nothing to gain from living in that file and a merge-conflict surface
 * to lose.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/newsletter")
public class NewsletterController {

    private final NewsletterService newsletterService;

    public NewsletterController(NewsletterService newsletterService) {
        this.newsletterService = newsletterService;
    }

    /** The recipient picker's data source - every active tenant, with its opt-in flag and resolved admin email (if any). */
    @GetMapping("/recipients")
    public List<NewsletterRecipient> listRecipients() {
        return newsletterService.listRecipients();
    }

    @PostMapping
    public NewsletterSendResult send(@RequestBody NewsletterSendRequest request) {
        String sentBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        return newsletterService.send(request, sentBy);
    }
}
