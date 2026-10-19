package com.agenticai.core.devices.sentinel;

import com.agenticai.core.tenancy.account.AccountDeletionRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes a user's own Sentinel events (health / safety findings synced from
 * their phone) when they delete their account in a shared workspace - see
 * {@link AccountDeletionRequested}. Runs synchronously on the request thread,
 * so {@code TenantContext} (and therefore the tenant schema) is still the
 * caller's; an exception here aborts the account deletion before the
 * Keycloak user is removed.
 *
 * <p>{@code mobile_device} rows are NOT touched: that table has no owner
 * column (a device is paired to the workspace, not to a person), so there is
 * nothing attributable to delete safely.
 */
@Component
public class MobileSentinelAccountDeletionListener {

    private static final Logger log = LoggerFactory.getLogger(MobileSentinelAccountDeletionListener.class);

    private final MobileSentinelEventRepository events;

    public MobileSentinelAccountDeletionListener(MobileSentinelEventRepository events) {
        this.events = events;
    }

    @EventListener
    public void onAccountDeletion(AccountDeletionRequested event) {
        int deleted = events.deleteAllOfUser(event.userId());
        log.info("Account deletion: removed {} mobile sentinel event(s) for a user in tenant '{}'",
            deleted, event.tenantId());
    }
}
