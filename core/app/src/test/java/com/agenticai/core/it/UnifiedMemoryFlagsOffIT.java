package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.memory.spi.CompetenceRecorder;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryRecallRequest;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.memory.spi.UnifiedMemoryRecall;
import com.agenticai.core.agents.memory.spi.VerifiedOutcome;
import com.agenticai.core.agents.memorystore.DisabledMemoryProposalSink;
import com.agenticai.core.agents.memorystore.MemoryItemExportController;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.JourneyRecallBridge;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * Unified agent memory, flags OFF (the shipped default, nothing configured): the real application boots with the
 * disabled sink, and a turn, a recall, a competence outcome and a proposal leave no row anywhere and change not one
 * byte of the prompt. The ON counterpart is {@link UnifiedMemoryJourneyIT}.
 */
class UnifiedMemoryFlagsOffIT extends UnifiedMemoryBootBase {

    private static final String SCHEMA = "tenant_it_umj_off";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "umj_off");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    @Autowired MemoryProposalSink sink;
    @Autowired UnifiedMemoryRecall recall;
    @Autowired CompetenceRecorder competence;
    @Autowired MemoryItemExportController export;
    @Autowired AgentChatService chat;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private int rows(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + "." + table, Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    void nothingIsWrittenRecalledOrAddedToThePromptWhenEveryFlagIsOff() {
        tenantSchema(SCHEMA);
        TenantContext.set(TenantContext.of(SCHEMA, "alice", Set.of()));
        UUID agent = newAgent("flags-off");

        assertThat(sink).as("the shipped default sink").isInstanceOf(DisabledMemoryProposalSink.class);
        assertThat(sink.propose(new MemoryWriteProposal(
                        agent, "alice", MemoryKind.EPISODIC, "Episode (chat): nothing", MemoryProvenanceRecord.TRUSTED, null, false)))
                .isEmpty();

        // The chat turn's episode hook adds not one operator to the reply: it hands back the very same Flux.
        Flux<String> turn = Flux.just("hello");
        assertThat(EpisodeRecordingGateway.observe(turn, agent, "s-1", Instant.now(), Optional.of(TenantContext.current().orElseThrow()), null))
                .isSameAs(turn);

        competence.record(new VerifiedOutcome(agent, "tools:search", VerifiedOutcome.Signal.TOOL_SUCCESS));
        assertThat(recall.compose(new MemoryRecallRequest(agent, "alice", "anything", Set.of(), 0, 800)).degradedReason())
                .isEqualTo(UnifiedMemoryRecall.DISABLED);

        JourneyRecallBridge.Recalled prompt = JourneyRecallBridge.recall(chat, agent, "alice", "what do you remember?");
        assertThat(prompt.block()).as("zero prompt change").isNull();
        assertThat(prompt.facts()).isZero();
        assertThat(prompt.unified()).isEmpty();
        assertThat(prompt.note()).isNull();

        assertThat(rows("memory_item")).isZero();
        assertThat(rows("memory_item_source")).isZero();
        assertThat(rows("memory_event")).isZero();
        assertThat(rows("memory_competence")).isZero();
        assertThat(rows("memory_tombstone")).isZero();
        assertThatThrownBy(() -> export.export(() -> "alice", 10)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
