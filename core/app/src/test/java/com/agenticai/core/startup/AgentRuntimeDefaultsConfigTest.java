package com.agenticai.core.startup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.context.ChatContextCompaction;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hooks.AgentHookConfigRepository;
import com.agenticai.core.agents.hooks.AgentHookConfigService;
import com.agenticai.core.agents.hooks.AgentHookRuntime;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.skills.AgentSkillConfigRepository;
import com.agenticai.core.agents.skills.BuiltInSkillCatalog;
import com.agenticai.core.agents.skills.SkillRepository;
import com.agenticai.core.agents.skills.SkillSupplyChainService;
import com.agenticai.core.agents.toolpolicy.AgentToolArgPolicyRepository;
import com.agenticai.core.agents.toolpolicy.ToolArgPolicyService;
import com.agenticai.core.agents.tools.AgentTaintPolicy;
import com.agenticai.core.agents.tools.StepJournal;
import com.agenticai.core.agents.tools.StepJournalRepository;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.guardrails.taint.TaintMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * The agent-runtime defaults the owner set on 2026-10-23 (docs/engineering/agent-runtime-defaults-review.md),
 * proved on the EFFECTIVE configuration: the shipped {@code application.yml} (what lite, full and team
 * deployments inherit), with {@code application-lite.yml} and {@code application-personal.yml} layered on the
 * way Spring Boot layers them, then the real components built on that environment.
 *
 * <table>
 *   <caption>The decision</caption>
 *   <tr><th>key</th><th>base</th><th>personal</th></tr>
 *   <tr><td>app.agent-hooks.enabled</td><td>true</td><td>false</td></tr>
 *   <tr><td>app.tool-arg-policies.enabled</td><td>true</td><td>false</td></tr>
 *   <tr><td>app.agents.taint.mode</td><td>observe</td><td>enforce (unchanged)</td></tr>
 *   <tr><td>app.skills.supply-chain.enforce</td><td>true</td><td>false</td></tr>
 *   <tr><td>app.context.compaction.enabled</td><td>true</td><td>true</td></tr>
 *   <tr><td>app.agent-jobs.durability.step-journal-enabled</td><td>true</td><td>true</td></tr>
 * </table>
 *
 * Everything else in the review keeps its value, which the last tests pin so a flip cannot widen by accident.
 */
class AgentRuntimeDefaultsConfigTest {

    private static ConfigurableEnvironment environmentOf(String... profiles) {
        ConfigurableEnvironment[] holder = new ConfigurableEnvironment[1];
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        profiles.length == 0 ? new String[0] : new String[] {"spring.profiles.active=" + String.join(",", profiles)})
                .run(context -> holder[0] = context.getEnvironment());
        return holder[0];
    }

    private static ConfigurableEnvironment base() {
        return environmentOf();
    }

    private static ConfigurableEnvironment personal() {
        ConfigurableEnvironment env = environmentOf("personal");
        assertThat(env.acceptsProfiles(Profiles.of("personal"))).as("the personal profile is active").isTrue();
        return env;
    }

    private static ConfigurableEnvironment lite() {
        ConfigurableEnvironment env = environmentOf("lite");
        assertThat(env.acceptsProfiles(Profiles.of("lite"))).as("the lite profile is active").isTrue();
        return env;
    }

    // ------------------------------------------------------------------------------------ effective values

    @Test
    void baseAndLiteShipTheFlippedDefaults() {
        for (ConfigurableEnvironment env : new ConfigurableEnvironment[] {base(), lite()}) {
            assertThat(env.getProperty("app.agent-hooks.enabled")).isEqualTo("true");
            assertThat(env.getProperty("app.tool-arg-policies.enabled")).isEqualTo("true");
            assertThat(env.getProperty("app.agents.taint.mode")).isEqualTo("observe");
            assertThat(env.getProperty("app.skills.supply-chain.enforce")).isEqualTo("true");
            assertThat(env.getProperty("app.context.compaction.enabled")).isEqualTo("true");
            assertThat(env.getProperty("app.agent-jobs.durability.step-journal-enabled")).isEqualTo("true");
        }
    }

    @Test
    void personalKeepsHooksPoliciesAndSupplyChainEnforceOffAndTaintEnforcedButTurnsCompactionAndTheJournalOn() {
        ConfigurableEnvironment env = personal();

        assertThat(env.getProperty("app.agent-hooks.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.tool-arg-policies.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.agents.taint.mode")).isEqualTo("enforce");
        assertThat(env.getProperty("app.skills.supply-chain.enforce")).isEqualTo("false");
        assertThat(env.getProperty("app.context.compaction.enabled")).isEqualTo("true");
        assertThat(env.getProperty("app.agent-jobs.durability.step-journal-enabled")).isEqualTo("true");
    }

    @Test
    void personalDoesNotLoosenAnythingTheBaseTightened() {
        // Where personal differs from the base it is only because one person is also the approver and nobody authors
        // hooks, policies or skill approvals; the taint gate is STRICTER here than in the base.
        assertThat(TaintMode.parse(personal().getProperty("app.agents.taint.mode"))).isEqualTo(TaintMode.ENFORCE);
        assertThat(TaintMode.parse(base().getProperty("app.agents.taint.mode"))).isEqualTo(TaintMode.OBSERVE);
        assertThat(personal().getProperty("app.skills.supply-chain.enabled", "true"))
                .as("scanning and pinning stay on in personal; only the withholding is off")
                .isEqualTo("true");
    }

    @Test
    void everyFlippedDefaultStaysOverridableFromTheEnvironment() {
        ConfigurableEnvironment[] holder = new ConfigurableEnvironment[1];
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "AGENT_HOOKS_ENABLED=false",
                        "TOOL_ARG_POLICIES_ENABLED=false",
                        "APP_AGENTS_TAINT_MODE=off",
                        "SKILLS_SUPPLY_CHAIN_ENFORCE=false",
                        "APP_CONTEXT_COMPACTION_ENABLED=false",
                        "AGENT_JOB_STEP_JOURNAL_ENABLED=false")
                .run(context -> holder[0] = context.getEnvironment());
        ConfigurableEnvironment env = holder[0];

        assertThat(env.getProperty("app.agent-hooks.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.tool-arg-policies.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.agents.taint.mode")).isEqualTo("off");
        assertThat(env.getProperty("app.skills.supply-chain.enforce")).isEqualTo("false");
        assertThat(env.getProperty("app.context.compaction.enabled")).isEqualTo("false");
        assertThat(env.getProperty("app.agent-jobs.durability.step-journal-enabled")).isEqualTo("false");
    }

    // ------------------------------------------------------------------------------------ the rest is untouched

    @Test
    void everythingElseInTheReviewKeepsItsValueInBaseAndPersonal() {
        for (ConfigurableEnvironment env : new ConfigurableEnvironment[] {base(), personal()}) {
            assertThat(env.getProperty("app.turn-journal.enabled")).isEqualTo("false");
            assertThat(env.getProperty("app.mcp.tool-pinning.mode")).isEqualTo("warn");
            assertThat(env.getProperty("app.tools.classification.trifecta-enforcement")).isEqualTo("warn");
            assertThat(env.getProperty("app.tools.classification.unclassified-action")).isEqualTo("allow");
            assertThat(env.getProperty("app.chat.escalation.enabled")).isEqualTo("false");
            assertThat(env.getProperty("app.swarm.verifier.enabled")).isEqualTo("false");
            assertThat(env.getProperty("app.agent-jobs.durability.resume-from-journal")).isEqualTo("false");
            assertThat(env.getProperty("app.agent-jobs.durability.resume-rerun-incomplete-turn")).isEqualTo("false");
            assertThat(env.getProperty("app.agent-jobs.ownership.enabled")).isEqualTo("false");
            assertThat(env.getProperty("app.skills.supply-chain.enabled")).isEqualTo("true");
        }
    }

    @Test
    void theAuxiliaryCompactionModelIsUnsetSoTheAgentsOwnModelWritesTheSummary() {
        for (ConfigurableEnvironment env : new ConfigurableEnvironment[] {base(), personal()}) {
            assertThat(env.getProperty("app.auxiliary-model.compaction.provider", "")).isEmpty();
            assertThat(env.getProperty("app.auxiliary-model.compaction.model", "")).isEmpty();
            assertThat(env.getProperty("app.auxiliary-model.use-agent-model")).isEqualTo("true");
        }
    }

    @Test
    void jobResumeRerunAndOwnershipBindToFalseWhileTheStepJournalBindsToTrue() {
        for (ConfigurableEnvironment env : new ConfigurableEnvironment[] {base(), personal()}) {
            AgentJobProperties jobs =
                    Binder.get(env).bind("app.agent-jobs", AgentJobProperties.class).get();

            assertThat(jobs.getDurability().isStepJournalEnabled()).isTrue();
            assertThat(jobs.getDurability().isResumeFromJournal()).isFalse();
            assertThat(jobs.getDurability().isResumeRerunIncompleteTurn()).isFalse();
            assertThat(jobs.getOwnership().isEnabled()).isFalse();
        }
    }

    // ------------------------------------------------------------------------------------ the components are active

    private static ApplicationContextRunner components(String... profiles) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        profiles.length == 0 ? new String[0] : new String[] {"spring.profiles.active=" + String.join(",", profiles)})
                .withBean(AgentService.class, () -> mock(AgentService.class))
                .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
                .withBean(AgentHookConfigRepository.class, () -> mock(AgentHookConfigRepository.class))
                .withBean(AgentToolArgPolicyRepository.class, () -> mock(AgentToolArgPolicyRepository.class))
                .withBean(SkillRepository.class, () -> mock(SkillRepository.class))
                .withBean(AgentSkillConfigRepository.class, () -> mock(AgentSkillConfigRepository.class))
                .withBean(StepJournalRepository.class, () -> mock(StepJournalRepository.class))
                .withBean(BuiltInSkillCatalog.class, BuiltInSkillCatalog::new)
                .withUserConfiguration(
                        AgentHookConfigService.class,
                        AgentHookRuntime.class,
                        ToolArgPolicyService.class,
                        AgentTaintPolicy.class,
                        SkillSupplyChainService.class,
                        ChatContextCompaction.class,
                        StepJournal.class);
    }

    @Test
    void inTheBaseProfileEveryFlippedComponentIsActive() {
        components()
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AgentHookConfigService.class).isEnabled()).isTrue();
                            assertThat(context.getBean(AgentHookRuntime.class).isEnabled()).isTrue();
                            assertThat(context.getBean(ToolArgPolicyService.class).isEnabled()).isTrue();
                            assertThat(context.getBean(AgentTaintPolicy.class).mode()).isEqualTo(TaintMode.OBSERVE);
                            assertThat(context.getBean(SkillSupplyChainService.class).isEnforcing()).isTrue();
                            assertThat(context.getBean(ChatContextCompaction.class).isEnabled()).isTrue();
                            StepJournal journal = context.getBean(StepJournal.class);
                            assertThat(journal.isEnabled()).isTrue();
                            assertThat(journal.isResumeEnabled()).as("resume stays off").isFalse();
                        });
    }

    @Test
    void inThePersonalProfileTheComponentsFollowThePersonalValues() {
        components("personal")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AgentHookConfigService.class).isEnabled()).isFalse();
                            assertThat(context.getBean(AgentHookRuntime.class).isEnabled()).isFalse();
                            assertThat(context.getBean(ToolArgPolicyService.class).isEnabled()).isFalse();
                            assertThat(context.getBean(AgentTaintPolicy.class).mode()).isEqualTo(TaintMode.ENFORCE);
                            SkillSupplyChainService chain = context.getBean(SkillSupplyChainService.class);
                            assertThat(chain.isEnabled()).isTrue();
                            assertThat(chain.isEnforcing()).isFalse();
                            assertThat(context.getBean(ChatContextCompaction.class).isEnabled()).isTrue();
                            assertThat(context.getBean(StepJournal.class).isEnabled()).isTrue();
                            assertThat(context.getBean(StepJournal.class).isResumeEnabled()).isFalse();
                        });
    }

    @Test
    void withNoConfigurationAtAllTheCodeDefaultsAgreeWithTheShippedYml() {
        // A context without application.yml (a slim test, an embedded use) must not silently differ from production.
        new ApplicationContextRunner()
                .withBean(AgentService.class, () -> mock(AgentService.class))
                .withBean(AuditLogService.class, () -> mock(AuditLogService.class))
                .withBean(AgentHookConfigRepository.class, () -> mock(AgentHookConfigRepository.class))
                .withBean(AgentToolArgPolicyRepository.class, () -> mock(AgentToolArgPolicyRepository.class))
                .withBean(SkillRepository.class, () -> mock(SkillRepository.class))
                .withBean(AgentSkillConfigRepository.class, () -> mock(AgentSkillConfigRepository.class))
                .withBean(StepJournalRepository.class, () -> mock(StepJournalRepository.class))
                .withBean(BuiltInSkillCatalog.class, BuiltInSkillCatalog::new)
                .withUserConfiguration(
                        AgentHookConfigService.class,
                        AgentHookRuntime.class,
                        ToolArgPolicyService.class,
                        AgentTaintPolicy.class,
                        SkillSupplyChainService.class,
                        ChatContextCompaction.class,
                        StepJournal.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(AgentHookRuntime.class).isEnabled()).isTrue();
                            assertThat(context.getBean(ToolArgPolicyService.class).isEnabled()).isTrue();
                            assertThat(context.getBean(AgentTaintPolicy.class).mode()).isEqualTo(TaintMode.OBSERVE);
                            assertThat(context.getBean(SkillSupplyChainService.class).isEnforcing()).isTrue();
                            assertThat(context.getBean(ChatContextCompaction.class).isEnabled()).isTrue();
                            assertThat(context.getBean(StepJournal.class).isEnabled()).isTrue();
                        });
    }
}
