package com.agenticai.core.google;

import com.agenticai.core.google.dto.GoogleConnectionResponse;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What {@link GoogleOAuthController} needs from {@link GoogleOAuthService}, reduced to DTOs so the
 * controller never holds a {@link GoogleCredential} entity.
 */
@Service
public class GoogleOAuthViewService {

    /**
     * What the callback needs from a completed connect: whether youtube.readonly is already
     * granted, and the account email.
     */
    public record ConnectOutcome(boolean youtubeReadonlyGranted, String connectedEmail) {}

    private final GoogleOAuthService oauthService;

    public GoogleOAuthViewService(GoogleOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @Transactional(readOnly = true)
    public Optional<GoogleConnectionResponse> statusResponse() {
        return oauthService.status().map(GoogleConnectionResponse::from);
    }

    /** {@link GoogleOAuthService#connect} reduced to what the OAuth callback inspects. */
    @Transactional
    public ConnectOutcome connectOutcome(
            String code, String subjectKey, String purpose, String nonce) {
        GoogleCredential credential = oauthService.connect(code, subjectKey, purpose, nonce);
        return new ConnectOutcome(
                credential.hasScope(GoogleScopes.YOUTUBE_READONLY), credential.getConnectedEmail());
    }
}
