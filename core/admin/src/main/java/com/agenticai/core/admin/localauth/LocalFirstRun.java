package com.agenticai.core.admin.localauth;

import java.util.function.Supplier;

/**
 * Marks the signup running on THIS thread as the first-run owner signup, so {@link
 * LocalUserAdminClient#createUserForSignup} creates that one account already in the "setup pending"
 * state (sign-in refused until the one-time setup link sets a password). Doing it inside the
 * account insert, rather than as a follow-up update, closes the window in which a crash between the
 * two would leave an owner account with a random password nobody knows.
 *
 * <p>A thread-local is correct here because tenant provisioning is synchronous on the calling
 * thread; the marker is cleared in a {@code finally} and never crosses a thread hop.
 */
public final class LocalFirstRun {

    private static final ThreadLocal<Boolean> OWNER_SIGNUP = new ThreadLocal<>();

    private LocalFirstRun() {}

    /** Runs {@code action} with the owner-signup marker set. */
    public static <T> T asOwnerSignup(Supplier<T> action) {
        OWNER_SIGNUP.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            OWNER_SIGNUP.remove();
        }
    }

    static boolean ownerSignupActive() {
        return Boolean.TRUE.equals(OWNER_SIGNUP.get());
    }
}
