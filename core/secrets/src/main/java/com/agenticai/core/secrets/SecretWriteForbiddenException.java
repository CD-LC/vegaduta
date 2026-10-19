package com.agenticai.core.secrets;

/**
 * Thrown when a caller tries to create, rotate or remove a
 * {@link SecretScope#TENANT}-scoped secret without workspace-admin rights.
 *
 * <p><b>403, not 404.</b> Unlike the tenant-isolation exceptions elsewhere in
 * this codebase, there is no existence secret to protect here: every member can
 * already <i>see</i> every TENANT-scoped secret's name in the vault list (that
 * is the point of a shared vault - you need to know STRIPE_KEY exists to
 * reference it). Only writing is restricted, so hiding the refusal behind a 404
 * would tell the caller nothing new while making a legitimate permission
 * problem look like a bug.
 *
 * <p>USER-scoped secrets take the opposite posture: another member's personal
 * secret is not listed at all, and addressing one by id raises
 * "No such secret" from {@code SecretVaultService#requireVisible} - a 404-shaped
 * answer, because there the existence of the row genuinely is the secret.
 */
public class SecretWriteForbiddenException extends RuntimeException {

    public SecretWriteForbiddenException() {
        super("Only a workspace admin can add, replace or remove a shared workspace secret. "
            + "You can still store secrets scoped to yourself.");
    }
}
