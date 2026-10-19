package com.agenticai.core.rl.api;

/**
 * A proposed bandit arm was refused before persistence - malformed
 * {@code armConfigJson}, a config shape the runtime does not read, or a
 * provider/model this tenant cannot actually use.
 *
 * <p>Mapped to {@code 400 Bad Request} by {@code GlobalExceptionHandler}. The
 * message is written for a human operator and is safe to show verbatim: silent
 * failure is precisely what let {@code MODEL_ROUTE} sit enabled-with-zero-arms
 * in production, so every rejection here says exactly what was wrong and what
 * to do instead.
 */
public class RlArmRejectedException extends RuntimeException {

    public RlArmRejectedException(String message) {
        super(message);
    }
}
