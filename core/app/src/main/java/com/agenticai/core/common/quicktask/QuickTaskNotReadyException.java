package com.agenticai.core.common.quicktask;

/**
 * Client-actionable "one prerequisite for Quick Task isn't set up yet" -
 * either the tenant has no agent at all, or this user hasn't set their own
 * phone number. Distinct from the individual dispatch failures
 * ({@code ChannelDeliveryException}, {@code TwilioNotConfiguredException}),
 * which fire once the task has actually run and is trying to deliver.
 */
public class QuickTaskNotReadyException extends RuntimeException {

    public QuickTaskNotReadyException(String message) {
        super(message);
    }
}
