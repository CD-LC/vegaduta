package com.agenticai.core.agents.tools.e2b;

import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolCredentialStatusResponse;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.sdlc.E2bAccess;
import com.agenticai.core.sdlc.E2bPlatformAccessProvider;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The platform-managed E2B credential (web-admin "Tool credentials" E2B card: API key + template id
 * in {@code extraConfig}) as an {@link E2bPlatformAccessProvider}. Same posture as {@code
 * PlatformElevenLabsSpeakCapabilityProvider}: read fresh on every call because a platform admin can
 * set, rotate or disable it at runtime; a key without a template id is "not configured" (there is
 * nothing to boot).
 *
 * <p>This class only supplies the credential. Whether it is used at all is decided by {@code
 * E2bAccessResolver} ({@code app.sandbox.platform-e2b.enabled}, default false), and every run on it
 * is metered by {@link PlatformSandboxRunQuota}.
 */
@Component
public class PlatformE2bAccessProvider implements E2bPlatformAccessProvider {

    private final ObjectProvider<PlatformToolCredentialService> credentialService;

    public PlatformE2bAccessProvider(
            ObjectProvider<PlatformToolCredentialService> credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public Optional<E2bAccess> resolve() {
        PlatformToolCredentialService service = credentialService.getIfAvailable();
        if (service == null) {
            return Optional.empty();
        }
        String templateId =
                service.resolveActiveExtraConfig(PlatformToolKey.E2B)
                        .map(String::trim)
                        .orElse(null);
        if (templateId == null || templateId.isEmpty()) {
            return Optional.empty();
        }
        String apiKey = service.resolveActiveApiKey(PlatformToolKey.E2B).orElse(null);
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new E2bAccess(apiKey, templateId, E2bAccess.Source.PLATFORM));
    }

    /** Status read only - no key decryption on the per-turn availability path. */
    @Override
    public boolean isConfigured() {
        PlatformToolCredentialService service = credentialService.getIfAvailable();
        if (service == null) {
            return false;
        }
        PlatformToolCredentialStatusResponse status = service.status(PlatformToolKey.E2B);
        return status != null
                && status.configured()
                && status.extraConfig() != null
                && !status.extraConfig().isBlank();
    }
}
