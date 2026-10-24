package com.agenticai.core.agents;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.context.ContextCompactionProperties;
import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.skills.SkillDistillationProperties;
import com.agenticai.core.agents.skills.SkillPromptProperties;
import com.agenticai.core.agents.tools.SmallModelProperties;
import com.agenticai.core.agents.turn.ChatLimitsProperties;
import com.agenticai.core.agents.turn.ChatParityProperties;
import com.agenticai.core.agents.turn.ChatResilienceProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * Package 3c-1: the settings that used to be constants bind from the real {@code application.yml} (read from
 * {@code core/app-config}) and equal the code defaults a context without that file falls back to, so the two
 * cannot drift apart. A change of a default has to be made in both places and shows up here.
 */
class Package3c1DefaultsTest {

    private static Binder binder() throws Exception {
        // PACKAGE3C1_YML=<file> points the test at another copy of the file (for debugging a broken yml).
        String override = System.getenv("PACKAGE3C1_YML");
        Path yml = Path.of(override != null ? override : "../app-config/src/main/resources/application.yml")
                .toAbsolutePath().normalize();
        assertThat(Files.exists(yml)).as(yml.toString()).isTrue();
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new FileSystemResource(yml));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void memoryLimits() throws Exception {
        MemoryLimitsProperties fromYml = binder().bind("app.memory.limits", MemoryLimitsProperties.class).get();
        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new MemoryLimitsProperties());
        assertThat(fromYml.getAgentMaxMemories()).isEqualTo(50);
        assertThat(fromYml.getTenantMaxMemories()).isEqualTo(300);
        assertThat(fromYml.getSessionMaxStoredEntries()).isEqualTo(400);
        assertThat(fromYml.getEdgeMemoryMaxFacts()).isEqualTo(24);
    }

    @Test
    void skillPrompt() throws Exception {
        SkillPromptProperties fromYml = binder().bind("app.skills.prompt", SkillPromptProperties.class).get();
        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new SkillPromptProperties());
        assertThat(fromYml.getMaxSkills()).isEqualTo(60);
        assertThat(fromYml.getMaxBlockChars()).isEqualTo(9000);
    }

    @Test
    void skillDistillation() throws Exception {
        SkillDistillationProperties fromYml =
                binder().bind("app.skills.distillation", SkillDistillationProperties.class).get();
        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new SkillDistillationProperties());
        assertThat(fromYml.getMinEpisodeReward()).isEqualByComparingTo("0.60");
    }

    @Test
    void smallModel() throws Exception {
        SmallModelProperties fromYml = binder().bind("app.chat.small-model", SmallModelProperties.class).get();
        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new SmallModelProperties());
        assertThat(fromYml.getToolCap()).isEqualTo(6);
    }

    @Test
    void chatLimits() throws Exception {
        ChatLimitsProperties fromYml = binder().bind("app.chat.limits", ChatLimitsProperties.class).get();
        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new ChatLimitsProperties());
        assertThat(fromYml.getAttachments().getMaxVideoBytes()).isEqualTo(25L * 1024 * 1024);
    }

    @Test
    void contextCompaction() throws Exception {
        ContextCompactionProperties fromYml =
                binder().bind("app.context.compaction", ContextCompactionProperties.class).get();
        assertThat(fromYml).usingRecursiveComparison().isEqualTo(new ContextCompactionProperties());
        assertThat(fromYml.getWindowRules()).hasSize(3);
    }

    @Test
    void parityAndResilienceAdditions() throws Exception {
        ChatParityProperties parity = binder().bind("app.chat.parity", ChatParityProperties.class).get();
        assertThat(parity.getVerifierHistoryWindow()).isEqualTo(new ChatParityProperties().getVerifierHistoryWindow()).isEqualTo(6);
        ChatResilienceProperties resilience = binder().bind("app.chat.resilience", ChatResilienceProperties.class).get();
        assertThat(resilience.getStructuredCallTimeout()).isEqualTo(new ChatResilienceProperties().getStructuredCallTimeout());
        assertThat(resilience.getHostedSelfHealCooldown()).isEqualTo(new ChatResilienceProperties().getHostedSelfHealCooldown());
    }

    @Test
    void semanticCacheKeysAreInTheYmlWithTheOldDefaults() throws Exception {
        Binder b = binder();
        assertThat(b.bind("app.chat.semantic-cache.similarity-threshold", Double.class).get()).isEqualTo(0.95);
        assertThat(b.bind("app.chat.semantic-cache.ttl-hours", Long.class).get()).isEqualTo(6L);
        assertThat(b.bind("app.chat.semantic-cache.max-entries-per-agent", Integer.class).get()).isEqualTo(200);
    }
}
