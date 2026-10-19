package com.agenticai.core.tenancy.dto;

/**
 * The logged-in user's own self-service account details: their phone number
 * (Simple mode's Quick Task feature delivers results there via WhatsApp/
 * SMS/a call) and their profile picture. {@code phoneNumber} is null until
 * the user has set it - never a placeholder. {@code avatarUrl} is null until
 * the user has uploaded a picture (AppShell falls back to initials in that
 * case); when present it is always the same backend-relative path,
 * {@code /api/account/avatar} - never a raw MinIO/S3 URL - so the frontend
 * fetches it through {@link com.agenticai.core.tenancy.AccountController
 * #getAvatar}, which carries the same bearer-token auth as every other API
 * call (a `<img src>` can't attach an Authorization header, so the frontend
 * fetches this path as a blob rather than using it directly as an image
 * src - see AvatarUploadModal.tsx / api.ts's getAccountAvatarBlob).
 */
public record AccountResponse(String email, String phoneNumber, String avatarUrl) {
}
