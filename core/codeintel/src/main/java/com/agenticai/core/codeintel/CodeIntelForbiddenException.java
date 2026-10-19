package com.agenticai.core.codeintel;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link CodeIntelController} when a non-admin browser user tries to
 * reach an action the console gates to ROLE_tenant-admin (resolving
 * code-intel's own approvals, or the autonomous SDLC agent). {@code
 * @ResponseStatus(FORBIDDEN)} maps it to a 403 automatically, so this stays
 * self-contained in {@code core/codeintel} rather than needing an entry in
 * {@code core/app}'s GlobalExceptionHandler - the same 403 posture that
 * handler already gives {@code ApprovalNotAuthorizedException}.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class CodeIntelForbiddenException extends RuntimeException {

    public CodeIntelForbiddenException(String message) {
        super(message);
    }
}
