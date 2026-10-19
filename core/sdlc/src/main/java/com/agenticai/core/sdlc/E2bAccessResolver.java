package com.agenticai.core.sdlc;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides which E2B account a {@code run_code} call runs on, and is the ONE answer to "can this
 * workspace execute code right now" (the tool resolver, the Code execution capability and the
 * data-analysis file gate all ask {@link #codeExecutionReady()}).
 *
 * <p>Precedence: the workspace's own credential wins whenever it is ACTIVE and has a
 * code-execution template (a BYOK tenant keeps paying its own E2B bill and is never metered by
 * the platform). Otherwise, only when {@code app.sandbox.platform-e2b.enabled} is true, the
 * platform-managed credential from {@link E2bPlatformAccessProvider} is used. Flag off (the
 * default) = exactly the tenant-only behaviour that existed before this class.
 *
 * <p>Scope: {@code run_code} only. The manual {@code /api/sdlc/sandbox} REST surface and tenant
 * custom-code tools ({@code E2bCustomToolExecutor}) deliberately stay on the tenant key via
 * {@link SdlcSandboxService#runCode(com.agenticai.core.sdlc.dto.RunCodeRequest)} - they are
 * unmetered paths the platform must not subsidise.
 */
@Service
public class E2bAccessResolver {

    private static final Logger log = LoggerFactory.getLogger(E2bAccessResolver.class);

    private final E2bCredentialService credentialService;
    private final ObjectProvider<E2bPlatformAccessProvider> platformProvider;
    private final boolean platformFallbackEnabled;

    public E2bAccessResolver(
        E2bCredentialService credentialService,
        ObjectProvider<E2bPlatformAccessProvider> platformProvider,
        @Value("${app.sandbox.platform-e2b.enabled:false}") boolean platformFallbackEnabled
    ) {
        this.credentialService = credentialService;
        this.platformProvider = platformProvider;
        this.platformFallbackEnabled = platformFallbackEnabled;
    }

    /** Whether the platform-managed fallback is switched on at all (says nothing about it being configured). */
    public boolean platformFallbackEnabled() {
        return platformFallbackEnabled;
    }

    /** True when the workspace's own E2B key is ACTIVE and has a code-execution template. */
    public boolean tenantReady() {
        return credentialService.isActive() && credentialService.hasCodeExecutionTemplate();
    }

    /** Cheap, non-throwing in the normal case: would {@link #resolveForCodeExecution()} succeed right now? */
    public boolean codeExecutionReady() {
        if (tenantReady()) {
            return true;
        }
        return platformFallbackEnabled && platformConfigured();
    }

    /**
     * The credential to run on. Tenant first, then the platform fallback.
     *
     * @throws E2bNotConfiguredException when neither is usable (mapped to 400 by GlobalExceptionHandler)
     */
    public E2bAccess resolveForCodeExecution() {
        if (tenantReady()) {
            return new E2bAccess(
                credentialService.resolveActiveApiKey(),
                credentialService.resolveCodeExecutionTemplateId(),
                E2bAccess.Source.TENANT);
        }
        if (platformFallbackEnabled) {
            Optional<E2bAccess> platform = platformAccess();
            if (platform.isPresent()) {
                return platform.get();
            }
            throw new E2bNotConfiguredException("Code execution is not available yet for this workspace.");
        }
        // Tenant-only mode: let the credential service raise its own specific message
        // (no credential at all vs. a credential without a template).
        credentialService.resolveActiveApiKey();
        credentialService.resolveCodeExecutionTemplateId();
        throw new E2bNotConfiguredException();
    }

    private boolean platformConfigured() {
        E2bPlatformAccessProvider provider = platformProvider.getIfAvailable();
        if (provider == null) {
            return false;
        }
        try {
            return provider.isConfigured();
        } catch (RuntimeException e) {
            log.warn("Platform E2B availability check failed - treating as not configured: {}", e.getMessage());
            return false;
        }
    }

    private Optional<E2bAccess> platformAccess() {
        E2bPlatformAccessProvider provider = platformProvider.getIfAvailable();
        if (provider == null) {
            return Optional.empty();
        }
        try {
            Optional<E2bAccess> access = provider.resolve();
            if (access == null || access.isEmpty()) {
                return Optional.empty();
            }
            // Never trust an implementation to label its own source: a platform key is always metered.
            E2bAccess a = access.get();
            return Optional.of(a.platformManaged() ? a : new E2bAccess(a.apiKey(), a.templateId(), E2bAccess.Source.PLATFORM));
        } catch (RuntimeException e) {
            log.warn("Platform E2B credential could not be resolved: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
