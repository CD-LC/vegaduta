package com.agenticai.core.agents.memory;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock the memory services (agent and tenant memory, the daily memory review, the memory self-service) read
 * "now" from for last-used stamps, ages and the review day. The system UTC clock, so behaviour is what it always was;
 * a test or an evaluation harness supplies its own {@code memoryClock} bean (or calls the services' {@code useClock})
 * to advance time without sleeping. Named, and injected by that name, so it never becomes the answer to an unqualified
 * {@code Clock} injection anywhere else.
 */
@Configuration
class MemoryClockConfiguration {

    static final String BEAN_NAME = "memoryClock";

    @Bean(BEAN_NAME)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(name = BEAN_NAME)
    Clock memoryClock() {
        return Clock.systemUTC();
    }
}
