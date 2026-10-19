package com.agenticai.core.sharing;

/**
 * Thrown by {@link ResourceAccessService#grant} (and by {@link ShareController}
 * before it, for the email-resolution step) when the target of a share
 * cannot be granted access - the email didn't resolve to any Keycloak user,
 * or it resolved to a user in a DIFFERENT tenant. Mapped to HTTP 403 (see
 * {@code GlobalExceptionHandler#handleInvalidGrantee} in {@code core/app}),
 * with a deliberately generic message.
 *
 * <p>Unlike {@link ResourceAccessDeniedException} (404 - hides whether the
 * RESOURCE exists), this is 403 by design: the caller here is always the
 * resource's own owner (or a tenant-admin) performing an authorized
 * share-management action, so there is no resource-existence secret to
 * protect. What the generic message DOES still hide is which of "no such
 * email in this tenant" / "that email belongs to a different tenant" / "that
 * email belongs to this tenant but something else went wrong" applies -
 * distinguishing those would let a tenant member enumerate valid email
 * addresses across other tenants by trial and error.
 */
public class InvalidGranteeException extends RuntimeException {

    public InvalidGranteeException() {
        super("Could not share with this recipient. Confirm they have an account in your organization.");
    }
}
