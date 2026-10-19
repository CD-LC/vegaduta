package com.agenticai.core.agents.branching;

import com.agenticai.core.tenancy.account.AccountDeletionRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes the conversation trees a member owns when they delete their account in a shared workspace - see
 * {@link AccountDeletionRequested}. Runs synchronously on the request thread, so {@code TenantContext} (and
 * therefore the tenant schema) is still the caller's; an exception here aborts the account deletion before the
 * Keycloak user is removed, so it can be retried. A sole member's trees go with the dropped schema instead.
 * Always safe to run: with branching off the tables are empty and this deletes nothing.
 */
@Component
public class ChatBranchAccountDeletionListener {

    private static final Logger log = LoggerFactory.getLogger(ChatBranchAccountDeletionListener.class);

    private final ChatBranchStore store;

    public ChatBranchAccountDeletionListener(ChatBranchStore store) {
        this.store = store;
    }

    @EventListener
    public void onAccountDeletion(AccountDeletionRequested event) {
        int deleted = store.deleteSessionsOwnedBy(event.userId());
        if (deleted > 0) {
            log.info("Account deletion: removed {} chat branch tree(s) for a user in tenant '{}'", deleted, event.tenantId());
        }
    }
}
