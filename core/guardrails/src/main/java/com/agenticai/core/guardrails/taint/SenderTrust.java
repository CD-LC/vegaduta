package com.agenticai.core.guardrails.taint;

/**
 * How far the author of an inbound message is trusted. Only the agent's owner and members of the
 * owning tenant are {@link #TRUSTED}; every other sender (a customer on WhatsApp or email, an
 * anonymous share-page visitor, a remote A2A or MCP peer) is {@link #UNTRUSTED}, and a sender whose
 * standing cannot be established is untrusted too - the decision fails closed.
 *
 * <p>An untrusted sender's message is content an outsider authored, so the turn it starts is
 * tainted before any tool runs (see {@link #seed}).
 */
public enum SenderTrust {
    TRUSTED,
    UNTRUSTED;

    /** TRUSTED only for an explicit {@code true}; null (unknown) is untrusted. */
    public static SenderTrust ofOwnerOrMember(Boolean ownerOrTenantMember) {
        return Boolean.TRUE.equals(ownerOrTenantMember) ? TRUSTED : UNTRUSTED;
    }

    /**
     * Marks {@code taint} with {@code source} unless the sender is trusted.
     *
     * @return true if the turn was tainted by this call because of this sender
     */
    public boolean seed(TurnTaint taint, String source) {
        if (this == TRUSTED || taint == null) {
            return false;
        }
        taint.mark(source);
        return true;
    }
}
