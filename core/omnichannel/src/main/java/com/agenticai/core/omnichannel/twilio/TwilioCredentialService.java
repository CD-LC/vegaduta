package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.twilio.dto.TwilioActivationRequest;
import com.agenticai.core.omnichannel.twilio.dto.TwilioCredentialResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import com.twilio.http.HttpClient;
import com.twilio.http.NetworkHttpClient;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.Account;
import java.util.List;
import java.util.Optional;
import org.apache.hc.client5.http.config.RequestConfig;

import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYOK activation for Twilio, mirroring
 * {@link com.agenticai.core.providers.ProviderService}'s pattern exactly:
 * validate with a lightweight real API call before persisting, encrypt at
 * rest, never return the plaintext token.
 *
 * <p>Deliberately builds an explicit {@link TwilioRestClient} per call
 * rather than using the Twilio SDK's {@code Twilio.init(sid, token)} static
 * global configuration - that global-state helper is a real footgun once
 * more than one tenant's credentials might exist in the same JVM (Phase 0's
 * multi-tenancy work), so this codebase never uses it.
 *
 * <p>Every method here is naturally tenant-scoped by Phase 0 Stage 4's
 * schema-per-tenant multi-tenancy - {@code repository.findFirstByOrderByCreatedAtAsc()}
 * finds "the one active row" within whatever schema the current connection
 * is pointed at, which is a different physical table per tenant, not a
 * shared one filtered by a tenant column. {@link #activate} additionally
 * registers a {@link TenantResolutionService} link so the inbound webhook -
 * which has no JWT and therefore no tenant context of its own - can later
 * work out which tenant a given Twilio Account SID belongs to.
 */
@Service
public class TwilioCredentialService {

    private final TwilioCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final TenantLimits tenantLimits;

    public TwilioCredentialService(
        TwilioCredentialRepository repository,
        EncryptionService encryptionService,
        TenantResolutionService tenantResolutionService,
        TenantSchemaResolver tenantSchemaResolver,
        TenantLimits tenantLimits
    ) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.tenantLimits = tenantLimits;
    }

    @Transactional
    public TwilioCredentialResponse activate(TwilioActivationRequest request) {
        // Plan gate - Twilio carries WhatsApp, SMS and voice, all paid-tier
        // channel surfaces (plan_limits.external_channels_allowed), enforced
        // at the service boundary so a direct API call cannot bypass it.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.externalChannelsAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Your plan does not include external channel connections - upgrade your plan to connect Twilio."
            );
        }
        if (request.hasApiKey() && (request.apiKeySecret() == null || request.apiKeySecret().isBlank())) {
            throw new ChannelConfigurationException("whatsapp", "apiKeySecret is required when apiKeySid is set.");
        }

        validate(request);

        String encryptedToken = encryptionService.encrypt(request.authToken());
        String encryptedApiKeySecret = request.hasApiKey() ? encryptionService.encrypt(request.apiKeySecret()) : null;
        String apiKeySid = request.hasApiKey() ? request.apiKeySid() : null;

        TwilioCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .map(existing -> {
                existing.reactivate(request.accountSid(), encryptedToken, apiKeySid, encryptedApiKeySecret, request.whatsAppFromNumber());
                return existing;
            })
            .orElseGet(() -> new TwilioCredential(request.accountSid(), encryptedToken, apiKeySid, encryptedApiKeySecret, request.whatsAppFromNumber()));

        TwilioCredentialResponse response = TwilioCredentialResponse.from(repository.save(credential));

        // Register (or repoint) which tenant schema this Account SID
        // belongs to - resolveCurrentTenantIdentifier() returns "public"
        // with the "security" profile off or no tenant context, which is
        // exactly correct: a single-tenant setup's one Twilio account links
        // to the one schema everything already lives in.
        tenantResolutionService.link(
            ExternalAccountProvider.TWILIO, request.accountSid(), tenantSchemaResolver.resolveCurrentTenantIdentifier()
        );

        return response;
    }

    @Transactional(readOnly = true)
    public Optional<TwilioCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(TwilioCredentialResponse::from);
    }

    /** Cheap, non-throwing check {@link com.agenticai.core.omnichannel.WhatsAppDispatchService} uses to pick a provider - same contract as {@code MetaWhatsAppCredentialService.isConfigured}. */
    @Transactional(readOnly = true)
    public boolean isActive() {
        return repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == TwilioCredentialStatus.ACTIVE)
            .isPresent();
    }

    /**
     * Tenant-initiated Twilio disconnect - marks the stored credential {@link TwilioCredentialStatus#DISCONNECTED}
     * rather than deleting it, so {@link #current} can still show what was
     * connected. {@link #resolveActive} already filters to {@code ACTIVE}
     * only, so this alone is enough to make every outbound caller ({@link
     * com.agenticai.core.omnichannel.WhatsAppDispatchService}) stop using
     * Twilio (Meta, if connected, already takes precedence) - no other
     * code needs to change. A no-op if nothing was ever connected.
     */
    @Transactional
    public void disconnect() {
        repository.findFirstByOrderByCreatedAtAsc().ifPresent(credential -> {
            credential.disconnect();
            repository.save(credential);
            // Drop the Account SID -> tenant link as well: inbound webhooks
            // already fail closed at resolveActive() (DISCONNECTED != ACTIVE),
            // but leaving the link would let a DIFFERENT tenant who later
            // connects this same SID inherit nothing (link() repoints) while
            // still resolving THIS tenant in the gap between disconnect and
            // that reconnect - an observable cross-tenant edge worth closing.
            tenantResolutionService.unlink(ExternalAccountProvider.TWILIO, credential.getAccountSid());
        });
    }

    /**
     * Resolves the active credential with its secrets decrypted, for {@link
     * TwilioWhatsAppChannelAdapter}/{@code WhatsAppDispatchService}/{@code
     * TwilioWebhookController} internal use only - never exposed via a
     * DTO/controller. {@code authToken} is always present (see {@code
     * TwilioCredential}'s javadoc); {@code apiKeySid}/{@code apiKeySecret}
     * are null unless this tenant configured one, in which case outbound
     * callers should prefer them over the Auth Token.
     */
    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        TwilioCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == TwilioCredentialStatus.ACTIVE)
            .orElseThrow(TwilioNotConfiguredException::new);
        return new DecryptedCredential(
            credential.getAccountSid(),
            encryptionService.decrypt(credential.getEncryptedAuthToken()),
            credential.usesApiKey() ? credential.getApiKeySid() : null,
            credential.usesApiKey() ? encryptionService.decrypt(credential.getEncryptedApiKeySecret()) : null,
            credential.getWhatsAppFromNumber()
        );
    }

    /**
     * Proves the credentials actually work with a real, non-billed call
     * before persisting - same reasoning as {@code ProviderValidator}
     * implementations. Validates via the API Key when one was supplied
     * (that's the credential outbound calls will actually use going
     * forward, so it's the one worth proving live); otherwise validates the
     * Account SID/Auth Token pair directly.
     */
    private void validate(TwilioActivationRequest request) {
        try {
            TwilioRestClient client = request.hasApiKey()
                ? new TwilioRestClient.Builder(request.apiKeySid(), request.apiKeySecret()).accountSid(request.accountSid()).build()
                : new TwilioRestClient.Builder(request.accountSid(), request.authToken()).build();
            Account.fetcher(request.accountSid()).fetch(client);
        } catch (RuntimeException e) {
            String checked = request.hasApiKey() ? "the Account SID and API Key SID/Secret" : "the Account SID and Auth Token";
            throw new ChannelConfigurationException("whatsapp", "Twilio credential validation failed - check " + checked + ": " + redactCredentials(e.getMessage(), request));
        }
    }

    /**
     * SDK error text is echoed into the API error above, and an SDK message can quote the request
     * it failed on - the exact credential values submitted here are the only secrets it could
     * carry, so blank those out before the text reaches a caller.
     */
    private static String redactCredentials(String message, TwilioActivationRequest request) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String result = message;
        for (String value : List.of(
                request.accountSid(), request.authToken(), request.apiKeySid(), request.apiKeySecret())) {
            if (value != null && value.length() >= 6) {
                result = result.replace(value, "[redacted]");
            }
        }
        return result;
    }

    public record DecryptedCredential(String accountSid, String authToken, String apiKeySid, String apiKeySecret, String whatsAppFromNumber) {

        // #617 follow-up ("when whatsapp message arrives, its takes too much
        // time to reply back... about improving the overall performance") -
        // every OTHER outbound channel client already has an explicit
        // timeout (MetaGraphApiClient: 10s); Twilio's
        // Java SDK was the one gap, silently relying on whatever its
        // NetworkHttpClient happens to hardcode internally (not exposed as a
        // simple builder property - see twilio-java's own "custom HTTP
        // client" advanced example, which is where this RequestConfig
        // pattern comes from). Without this, a slow/hanging Twilio API call
        // has no app-level ceiling and sits directly on the synchronous
        // webhook request thread that's waiting to reply.
        private static final int CONNECT_TIMEOUT_MS = 5000;
        private static final int RESPONSE_TIMEOUT_MS = 10000;

        /** @return the credential pair outbound REST calls should authenticate with - the API Key if one is configured, else the Account SID/Auth Token pair. */
        public TwilioRestClient buildOutboundClient() {
            HttpClient httpClient = timeBoundedHttpClient();
            return apiKeySid != null
                ? new TwilioRestClient.Builder(apiKeySid, apiKeySecret).accountSid(accountSid).httpClient(httpClient).build()
                : new TwilioRestClient.Builder(accountSid, authToken).httpClient(httpClient).build();
        }

        private static HttpClient timeBoundedHttpClient() {
            RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                .setResponseTimeout(Timeout.ofMilliseconds(RESPONSE_TIMEOUT_MS))
                .build();
            // The RequestConfig ctor builds the SDK's own pooled client internally -
            // same timeouts, no raw HttpClientBuilder call in this codebase.
            return new NetworkHttpClient(requestConfig);
        }
    }
}
