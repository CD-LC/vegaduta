package com.agenticai.core.templates.dto;

import java.time.Instant;

/**
 * One rating as shown on the public template page. Deliberately carries NO
 * rater identity - no subject, no tenant, no display name (there is no public
 * display-name infrastructure, and the Keycloak subject would be
 * correlatable). An anonymous review it is.
 */
public record TemplateRatingView(
    int stars,
    String review,
    Instant createdAt,
    Instant updatedAt
) {
}
