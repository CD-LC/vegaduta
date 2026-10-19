package com.agenticai.core.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * PUT /api/admin/users/{userId}/enabled - direct feedback: "provide disable
 * option on created user." {@code enabled: false} suspends sign-in without
 * deleting the account; {@code enabled: true} restores it.
 */
public record SetUserEnabledRequest(@NotNull Boolean enabled) {
}
