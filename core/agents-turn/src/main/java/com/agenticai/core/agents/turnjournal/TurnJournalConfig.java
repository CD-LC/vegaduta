package com.agenticai.core.agents.turnjournal;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the Postgres journal and installs it behind {@code app.turn-journal.enabled}. */
@Configuration
public class TurnJournalConfig {

    private final boolean enabled;
    private final TurnJournal journal;

    public TurnJournalConfig(
            JdbcTemplate jdbc, @Value("${app.turn-journal.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.journal = new JdbcTurnJournal(jdbc);
    }

    @Bean
    public TurnJournal turnJournal() {
        return journal;
    }

    @PostConstruct
    void install() {
        TurnJournalGateway.install(journal, enabled);
    }
}
