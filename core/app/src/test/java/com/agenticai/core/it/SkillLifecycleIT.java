package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.skills.Skill;
import com.agenticai.core.agents.skills.SkillDefinition;
import com.agenticai.core.agents.skills.SkillLifecycleService;
import com.agenticai.core.agents.skills.SkillRepository;
import com.agenticai.core.agents.skills.SkillService;
import com.agenticai.core.agents.skills.SkillStatus;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The skill lifecycle (V590) against a real Postgres and a real tenant schema: the JPQL bulk
 * updates, the derived queries, the version snapshots and the allow-list binding are all
 * statements the unit tests only see through mocks.
 */
@Tag("integration")
class SkillLifecycleIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_skills";

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txm;
    @Autowired private SkillService skillService;
    @Autowired private SkillLifecycleService lifecycle;
    @Autowired private SkillRepository skillRepository;
    @Autowired private ToolCallLogRepository toolCallRepository;
    @Autowired private ToolCallLogService toolCallService;

    @BeforeEach
    void setUp() {
        if (tenants.findBySchemaName(SCHEMA).isEmpty()) {
            Tenant t =
                    new Tenant(
                            SCHEMA,
                            TenantPlan.PREMIUM,
                            SCHEMA,
                            "agentic-ai",
                            AccountType.B2B,
                            ExperienceMode.SIMPLE);
            t.markActive();
            tenants.save(t);
        }
        assertThat(migrations.migrateAllActiveTenants()).as("schemas that failed").isEmpty();
        jdbc.execute("DELETE FROM " + SCHEMA + ".agent_skill_config");
        jdbc.execute("DELETE FROM " + SCHEMA + ".agent");
        jdbc.execute("DELETE FROM " + SCHEMA + ".skill");
        jdbc.execute("DELETE FROM " + SCHEMA + ".tool_call_log");
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private SkillDefinition create(String name) {
        return skillService.create(
                name, "Does " + name + ". Use when asked.", "1. Do the thing.", Map.of(), "alice");
    }

    @Test
    void aProposedDraftIsPendingUnversionedAndNotAdvertised() {
        SkillDefinition draft =
                skillService.propose(
                        "learned-flow", "Does a thing. Use when asked.", "1. Step.", "agent:x");
        assertThat(lifecycle.viewOf(draft.id()).orElseThrow().status()).isEqualTo(SkillStatus.PENDING);
        assertThat(lifecycle.versions(draft.id())).isEmpty();
        assertThat(skillRepository.findAllByEnabledTrueOrderByNameAsc()).isEmpty();

        SkillLifecycleService.Reviewed approved = lifecycle.approve(draft.id());

        assertThat(approved.view().status()).isEqualTo(SkillStatus.ACTIVE);
        assertThat(approved.view().version()).isEqualTo(1);
        assertThat(lifecycle.versions(draft.id())).extracting("version").containsExactly(1);
        assertThat(skillRepository.findAllByEnabledTrueOrderByNameAsc()).hasSize(1);
    }

    @Test
    void anEditOfALiveSkillIsANewVersionAndAnUnchangedSaveIsNot() {
        SkillDefinition s = create("edited-flow");
        assertThat(lifecycle.versions(s.id())).hasSize(1);

        skillService.update(s.id(), "Does it. Use when asked.", "1. Step one.\n2. Step two.", null);
        assertThat(lifecycle.versions(s.id())).extracting("version").containsExactly(2, 1);

        skillService.update(s.id(), "Does it. Use when asked.", "1. Step one.\n2. Step two.", null);
        assertThat(lifecycle.versions(s.id())).hasSize(2);
        assertThat(lifecycle.viewOf(s.id()).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void usageAndOutcomesAreCountedByBulkUpdates() {
        SkillDefinition live = create("counted-flow");
        SkillDefinition off = create("switched-off");
        skillService.setEnabled("switched-off", false);

        lifecycle.recordUse("counted-flow");
        lifecycle.recordUse("counted-flow");
        lifecycle.recordUse("switched-off");
        lifecycle.recordOutcome(List.of("counted-flow"), true);
        lifecycle.recordOutcome(List.of("counted-flow", "switched-off"), false);

        SkillLifecycleService.SkillView v = lifecycle.viewOf(live.id()).orElseThrow();
        assertThat(v.uses()).isEqualTo(2);
        assertThat(v.lastUsedAt()).isNotNull();
        assertThat(v.effectiveness()).isEqualTo(2.0 / 4.0);
        assertThat(lifecycle.viewOf(off.id()).orElseThrow().uses())
                .as("a skill that is not live is not 'used'")
                .isZero();
    }

    @Test
    void aStaleCopyOfASkillNeverWritesOldCountersBack() {
        SkillDefinition s = create("raced-flow");
        TransactionTemplate outer = new TransactionTemplate(txm);
        TransactionTemplate inner = new TransactionTemplate(txm);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(
                status -> {
                    Skill loaded = skillRepository.findById(s.id()).orElseThrow(); // uses = 0
                    inner.executeWithoutResult(
                            x -> skillRepository.recordUse("raced-flow", Instant.now()));
                    loaded.archive();
                    skillRepository.save(loaded);
                });

        Skill after = skillRepository.findById(s.id()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SkillStatus.ARCHIVED);
        assertThat(after.getUses()).as("the bulk increment survived the entity update").isEqualTo(1);
    }

    @Test
    void bindingAgentsEditsOnlyAllowListsAndNeverWidensOne() {
        SkillDefinition one = create("bound-one");
        SkillDefinition two = create("bound-two");
        UUID agent = insertAgent();
        UUID openAgent = insertAgent();
        skillService.setAgentSettings(agent, true, Set.of("bound-one", "bound-two"));

        SkillLifecycleService.AgentBinding keep = lifecycle.setAgents(one.id(), List.of(agent, openAgent));
        assertThat(keep.agentIds()).containsExactly(agent);
        assertThat(keep.unrestricted()).containsExactly(openAgent);

        // Dropping the agent from "bound-one" leaves it with "bound-two".
        lifecycle.setAgents(one.id(), List.of());
        assertThat(skillService.getAgentSettings(agent).allowedSkillNames()).containsExactly("bound-two");
        assertThat(lifecycle.views().get(two.id()).agentIds()).containsExactly(agent);

        // Dropping the last one would turn "only bound-two" into "every skill": refused.
        assertThatThrownBy(() -> lifecycle.setAgents(two.id(), List.of()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(skillService.getAgentSettings(agent).allowedSkillNames()).containsExactly("bound-two");
    }

    @Test
    void deletingASkillDeletesItsHistory() {
        SkillDefinition s = create("gone-flow");
        skillService.update(s.id(), "Does it. Use when asked.", "1. Changed.", null);
        assertThat(lifecycle.versions(s.id())).hasSize(2);

        skillService.delete(s.id());

        Integer left =
                jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".skill_version", Integer.class);
        assertThat(left).isZero();
    }

    @Test
    void theToolLogReadForOneTurnIsBoundedAndOrdered() {
        UUID agent = UUID.randomUUID();
        Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        toolCallRepository.save(call(agent, "old", t0.minusSeconds(60)));
        toolCallRepository.save(call(agent, "second", t0.plusSeconds(2)));
        toolCallRepository.save(call(agent, "first", t0.plusSeconds(1)));
        toolCallRepository.save(
                new ToolCallLog(
                        agent, "other-session", "elsewhere", "{}", "ok", null, t0.plusSeconds(1), t0.plusSeconds(2)));

        List<ToolCallLog> turn = toolCallService.findSince(agent, "s1", t0);

        assertThat(turn).extracting(ToolCallLog::getToolName).containsExactly("first", "second");
    }

    private UUID insertAgent() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO "
                        + SCHEMA
                        + ".agent (id, name, system_prompt, provider_name, model, max_tokens, temperature,"
                        + " knowledge_base_enabled, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,now(),now())",
                id,
                "Bot " + id,
                "p",
                "ANTHROPIC",
                "claude-x",
                1000,
                0.2,
                false);
        return id;
    }

    private static ToolCallLog call(UUID agent, String tool, Instant at) {
        return new ToolCallLog(agent, "s1", tool, "{}", "ok", null, at, at.plusMillis(5));
    }
}
