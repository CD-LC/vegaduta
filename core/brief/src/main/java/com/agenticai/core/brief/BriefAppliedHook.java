package com.agenticai.core.brief;

import java.util.UUID;

/**
 * Told, on the apply worker's thread with the caller's tenant context still set, after an apply has
 * succeeded and the brief is APPLIED. The proof ledger uses it to start the baseline eval run. A hook
 * that throws is logged and ignored: an apply never fails because of what listens to it.
 */
public interface BriefAppliedHook {

    void applied(UUID briefId);
}
