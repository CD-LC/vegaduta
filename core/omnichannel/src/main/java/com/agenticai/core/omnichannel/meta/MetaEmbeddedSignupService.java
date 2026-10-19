package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.meta.dto.EmbeddedSignupConfigResponse;
import com.agenticai.core.omnichannel.meta.dto.EmbeddedSignupExchangeRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaCredentialResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meta WhatsApp Embedded Signup - the guided alternative to manual BYOK
 * (see {@code MetaWhatsAppCredentialService}'s javadoc) that lets a tenant
 * connect WhatsApp with one button click and Meta's own hosted popup
 * instead of copying four values out of the Meta Developer Console by hand.
 *
 * <p><b>The crucial architectural difference from BYOK</b>: BYOK uses the
 * TENANT's own Meta App - each tenant creates their own app, so their own
 * App Secret signs their own inbound webhooks. Embedded Signup instead uses
 * THIS PLATFORM's single Meta App for every tenant who uses this path - the
 * platform, not the tenant, holds the App ID/App Secret/config_id below, and
 * every tenant using Embedded Signup ends up with that SAME platform App
 * Secret stored in their own {@code meta_whatsapp_credential} row (needed
 * because {@code MetaWhatsAppWebhookController} always looks up whichever
 * secret is stored for the resolved tenant to validate that tenant's inbound
 * webhook signature - it has no other way to know which secret signed a
 * given request). This is safe specifically because the platform's App
 * Secret is never exposed to any tenant or browser - only the App ID and
 * config_id are (see {@link #config()}), and those are meant to be public
 * (the App ID appears in every Facebook Login URL by design).
 *
 * <p><b>What the PLATFORM OPERATOR must do once, before this feature works
 * for anyone</b> (none of this is achievable by writing more code - it's
 * real, ongoing work on Meta's side): register a Meta App at
 * developers.facebook.com, add the WhatsApp product, request and pass Meta's
 * App Review for the Embedded Signup / {@code whatsapp_business_management}
 * permission, complete Meta Business verification, configure an Embedded
 * Signup "configuration" in the WhatsApp Manager (this produces the {@code
 * config_id} below), and host this backend on a real HTTPS domain (Meta
 * will not redirect to {@code localhost}). Until all of that is done,
 * {@link #isConfigured()} returns false and the guided button in {@code
 * ChannelsPanel.js} falls back to "not available yet, use manual setup
 * below" rather than pretending to work.
 *
 * <p>Choreography once configured (see {@code MetaGraphApiClient}'s javadoc
 * for the two Graph API calls this makes): frontend completes {@code
 * FB.login} and Meta's popup postMessage to get {@code code}/{@code
 * phoneNumberId}/{@code wabaId} -&gt; {@link #exchange} trades the code for
 * an access token using the platform's own App ID/Secret -&gt; subscribes
 * the platform's app to that WABA's webhooks -&gt; delegates to the exact
 * same {@code MetaWhatsAppCredentialService.activate(...)} manual BYOK uses,
 * so from that point on an Embedded-Signup-connected tenant is
 * indistinguishable from a manual-BYOK one to every other part of this
 * platform (WhatsAppDispatchService, the webhook controller, etc).
 */
@Service
public class MetaEmbeddedSignupService {

    private final MetaGraphApiClient graphApiClient;
    private final MetaWhatsAppCredentialService credentialService;
    private final String appId;
    private final String appSecret;
    private final String configId;
    private final String redirectUri;

    public MetaEmbeddedSignupService(
        MetaGraphApiClient graphApiClient,
        MetaWhatsAppCredentialService credentialService,
        @Value("${app.omnichannel.meta.embedded-signup.app-id:}") String appId,
        @Value("${app.omnichannel.meta.embedded-signup.app-secret:}") String appSecret,
        @Value("${app.omnichannel.meta.embedded-signup.config-id:}") String configId,
        @Value("${app.omnichannel.meta.embedded-signup.redirect-uri:}") String redirectUri
    ) {
        this.graphApiClient = graphApiClient;
        this.credentialService = credentialService;
        this.appId = appId;
        this.appSecret = appSecret;
        this.configId = configId;
        this.redirectUri = redirectUri;
    }

    public boolean isConfigured() {
        return !appId.isBlank() && !appSecret.isBlank() && !configId.isBlank() && !redirectUri.isBlank();
    }

    public EmbeddedSignupConfigResponse config() {
        requireConfigured();
        return new EmbeddedSignupConfigResponse(appId, configId);
    }

    @Transactional
    public MetaCredentialResponse exchange(EmbeddedSignupExchangeRequest request) {
        requireConfigured();
        String accessToken = graphApiClient.exchangeCodeForToken(request.code(), appId, appSecret, redirectUri);
        graphApiClient.subscribeAppToWaba(request.wabaId(), accessToken);
        // appSecret here is deliberately the PLATFORM's secret, not a
        // per-tenant one - see class javadoc for why that's correct.
        return credentialService.activate(new MetaActivationRequest(
            request.phoneNumberId(), request.wabaId(), accessToken, appSecret
        ));
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new MetaEmbeddedSignupNotConfiguredException();
        }
    }
}
