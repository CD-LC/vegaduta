package com.agenticai.core.admin.dto;

import java.util.List;

/**
 * {@code GET /api/auth/social-providers} response - see
 * {@link com.agenticai.core.admin.SocialAuthConfigController}. {@code
 * providers} is the list of Keycloak identity-provider aliases (e.g.
 * {@code "google"}, {@code "github"}, {@code "microsoft"}) that are both
 * (a) one of the three brokers this platform's login theme/SocialLoginRow
 * knows how to render a button for, AND (b) currently {@code enabled: true}
 * in the live realm.
 */
public record SocialProvidersResponse(List<String> providers) {
}
