package com.agenticai.core.tenancy.demo.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/demo/transcript-email} - "email me this
 * conversation", the sandbox's soft email capture.
 *
 * @param email REQUIRED - where to send the transcript. Also stored on
 *     {@code demo_session.email}, which is what makes the visitor eligible
 *     for the existing pre-expiry reminder; that dual use is the entire
 *     point of the feature and is stated on the /try page, not smuggled.
 * @param sessionId OPTIONAL - the browser's chat {@code sessionId} (the same
 *     client-generated value {@code POST /api/demo/chat} echoes back), so the
 *     web conversation can be read from the short-lived Redis session memory.
 *     DEVIATION FROM THE ORIGINAL {@code {email}} CONTRACT, stated loudly:
 *     without this the server has no way to find the web-chat history at all
 *     - Redis keys it by a value only the browser holds - and the fallback
 *     (the cross-channel mirror buffer) only covers Telegram/WhatsApp-preview
 *     turns. Optional and additive: omitting it still captures the email and
 *     still sends whatever the mirror buffer holds. Must be a UUID when
 *     present; anything else is ignored (see
 *     {@code DemoTranscriptEmailService} for the security reasoning).
 */
public record DemoTranscriptEmailRequest(
    @NotBlank(message = "email is required")
    @Email(message = "email must be a valid email address")
    @Size(max = 320, message = "email is too long")
    String email,

    @Size(max = 64, message = "sessionId is too long")
    String sessionId
) {
}
