package com.agenticai.core.omnichannel.session;

import com.agenticai.core.security.logging.LogSafe;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outcome counters ({@code omnichannel.inbound.executor}, tagged by outcome and channel) and the
 * rejection log line for {@link InboundChannelExecutor}. A null registry counts nothing; a metrics
 * failure never breaks message handling.
 */
final class InboundExecutorMetrics {

    private static final Logger log = LoggerFactory.getLogger(InboundChannelExecutor.class);
    private static final String METRIC = "omnichannel.inbound.executor";

    private final MeterRegistry registry;

    InboundExecutorMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void reject(String channel, String reason, String tenant) {
        log.warn(
                "Inbound {} task rejected ({} cap reached, tenant={})",
                LogSafe.clean(channel),
                LogSafe.clean(reason),
                LogSafe.clean(tenant));
        count("rejected_" + reason, channel);
    }

    void count(String outcome, String channel) {
        if (registry == null) {
            return;
        }
        try {
            registry.counter(METRIC, "outcome", outcome, "channel", channel).increment();
        } catch (RuntimeException ignored) {
            // metrics must never break message handling
        }
    }
}
