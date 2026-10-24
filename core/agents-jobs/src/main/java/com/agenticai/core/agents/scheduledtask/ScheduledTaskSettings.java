package com.agenticai.core.agents.scheduledtask;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@code app.scheduled-tasks.*} knobs, bound once and shared by the service, the runner and the tool resolver
 * so that no collaborator repeats a dozen {@code @Value} parameters. Every default is repeated here (not only in
 * application.yml) so slim test contexts construct, and the master switch defaults OFF: with it off nothing in this
 * package reads or writes anything, no tool is offered and the REST surface answers 404.
 */
@Component
public class ScheduledTaskSettings {

    private final boolean enabled;
    private final boolean freePlanEnabled;
    private final int maxPerTenant;
    private final int freeMaxPerTenant;
    private final int minIntervalMinutes;
    private final int maxConsecutiveFailures;
    private final int maxPromptChars;
    private final int catchUpWindowMinutes;
    private final boolean retryFailedOnce;
    private final int maxDeliveryChars;
    private final Set<String> deliveryChannels;

    @Autowired
    public ScheduledTaskSettings(
        @Value("${app.scheduled-tasks.enabled:false}") boolean enabled,
        @Value("${app.scheduled-tasks.free-plan-enabled:true}") boolean freePlanEnabled,
        @Value("${app.scheduled-tasks.max-per-tenant:10}") int maxPerTenant,
        @Value("${app.scheduled-tasks.free-max-per-tenant:3}") int freeMaxPerTenant,
        @Value("${app.scheduled-tasks.min-interval-minutes:15}") int minIntervalMinutes,
        @Value("${app.scheduled-tasks.max-consecutive-failures:3}") int maxConsecutiveFailures,
        @Value("${app.scheduled-tasks.max-prompt-chars:2000}") int maxPromptChars,
        @Value("${app.scheduled-tasks.catch-up-window-minutes:720}") int catchUpWindowMinutes,
        @Value("${app.scheduled-tasks.retry-failed-once:true}") boolean retryFailedOnce,
        @Value("${app.scheduled-tasks.max-delivery-chars:3500}") int maxDeliveryChars,
        @Value("${app.scheduled-tasks.delivery-channels:in_app,email,whatsapp,whatsapp_qr,telegram,slack,signal,matrix,imessage,a2a,voice}") String deliveryChannels
    ) {
        this.enabled = enabled;
        this.freePlanEnabled = freePlanEnabled;
        this.maxPerTenant = Math.max(1, maxPerTenant);
        this.freeMaxPerTenant = Math.max(1, freeMaxPerTenant);
        this.minIntervalMinutes = Math.max(1, minIntervalMinutes);
        this.maxConsecutiveFailures = Math.max(1, maxConsecutiveFailures);
        this.maxPromptChars = Math.max(50, maxPromptChars);
        this.catchUpWindowMinutes = Math.max(0, catchUpWindowMinutes);
        this.retryFailedOnce = retryFailedOnce;
        this.maxDeliveryChars = Math.max(200, maxDeliveryChars);
        Set<String> channels = new LinkedHashSet<>();
        if (deliveryChannels != null) {
            Arrays.stream(deliveryChannels.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .forEach(channels::add);
        }
        // In-app is the always-available floor: a task whose only delivery is disabled would have nowhere to put
        // its result.
        channels.add(ScheduledTaskDelivery.IN_APP.key());
        this.deliveryChannels = Set.copyOf(channels);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isFreePlanEnabled() {
        return freePlanEnabled;
    }

    public int getMaxPerTenant() {
        return maxPerTenant;
    }

    public int getFreeMaxPerTenant() {
        return freeMaxPerTenant;
    }

    public int getMinIntervalMinutes() {
        return minIntervalMinutes;
    }

    public int getMaxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }

    public int getMaxPromptChars() {
        return maxPromptChars;
    }

    public long getCatchUpWindowSeconds() {
        return catchUpWindowMinutes * 60L;
    }

    public boolean isRetryFailedOnce() {
        return retryFailedOnce;
    }

    public int getMaxDeliveryChars() {
        return maxDeliveryChars;
    }

    public boolean isDeliveryAllowed(ScheduledTaskDelivery delivery) {
        return deliveryChannels.contains(delivery.key());
    }

    public Set<String> getDeliveryChannels() {
        return deliveryChannels;
    }
}
