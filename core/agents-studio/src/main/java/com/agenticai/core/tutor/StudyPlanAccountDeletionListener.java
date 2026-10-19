package com.agenticai.core.tutor;

import com.agenticai.core.tenancy.account.AccountDeletionRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Deletes a member's own study plans (topics, progress, mistakes, review deck) when they delete
 * their account in a shared workspace - see {@link AccountDeletionRequested}. Runs synchronously on
 * the request thread, so {@code TenantContext} (and therefore the tenant schema) is still the
 * caller's; an exception here aborts the account deletion before the Keycloak user is removed, so
 * it can be retried. A sole member's plans go with the dropped schema instead.
 */
@Component
public class StudyPlanAccountDeletionListener {

    private static final Logger log = LoggerFactory.getLogger(StudyPlanAccountDeletionListener.class);

    private final StudyPlanService studyPlans;

    public StudyPlanAccountDeletionListener(StudyPlanService studyPlans) {
        this.studyPlans = studyPlans;
    }

    @EventListener
    public void onAccountDeletion(AccountDeletionRequested event) {
        long deleted = studyPlans.deleteAllOfUser(event.userId());
        log.info("Account deletion: removed {} study plan(s) for a user in tenant '{}'", deleted, event.tenantId());
    }
}
