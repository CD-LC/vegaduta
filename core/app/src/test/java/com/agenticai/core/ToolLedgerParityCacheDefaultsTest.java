package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.ChatCacheProperties;
import com.agenticai.core.agents.turn.ChatParityProperties;
import com.agenticai.core.agents.turn.ChatParityProperties.FastBufferTrigger;
import com.agenticai.core.agents.turn.ToolLedger;
import com.agenticai.core.agents.turn.ToolLedgerProperties;
import com.agenticai.core.chat.ToolCallRecovery;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code app.chat.tool-ledger.*}, {@code app.chat.parity.*}, {@code app.chat.cache.*} and {@code
 * app.chat.tools.text-call-recovery.*} are bound from the real application.yml, and the yml equals the code
 * defaults (the {@code chat/*.properties} resources, and the {@code @Value} defaults of {@link ToolCallRecovery})
 * that a context without the yml falls back to, so the two cannot drift apart.
 */
class ToolLedgerParityCacheDefaultsTest {

    private static StandardEnvironment environmentOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = environmentOverApplicationYml();
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void toolLedgerBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        ToolLedgerProperties fromYml = binderOverApplicationYml().bind("app.chat.tool-ledger", ToolLedgerProperties.class).get();
        ToolLedgerProperties code = new ToolLedgerProperties();

        assertThat(fromYml).usingRecursiveComparison().isEqualTo(code);
        assertThat(fromYml.isEnabled()).isTrue();
        assertThat(fromYml.isReplayEnabled()).isTrue();
        assertThat(fromYml.getMaxEntriesPerTurn()).isEqualTo(20);
        assertThat(fromYml.getMaxArgumentChars()).isEqualTo(160);
        assertThat(fromYml.getMaxResultChars()).isEqualTo(200);
        assertThat(fromYml.getMaxReplayCharsPerTurn()).isEqualTo(2000);
        assertThat(fromYml.getReplayTurns()).isEqualTo(6);
    }

    @Test
    void theLedgerTextsTellTheModelItIsThePlatformsRecordAndMayBeRelied() throws Exception {
        ToolLedgerProperties p = binderOverApplicationYml().bind("app.chat.tool-ledger", ToolLedgerProperties.class).get();

        assertThat(p.getHeader()).containsIgnoringCase("written by the platform").containsIgnoringCase("you may rely on them")
            .containsIgnoringCase("never write a record");
        assertThat(p.getTrustNote()).containsIgnoringCase("reliable evidence").containsIgnoringCase("counts as tool use")
            .containsIgnoringCase("never write such a record");
        assertThat(p.getLine()).contains("{tool}", "{arguments}", "{status}", "{detail}");
        assertThat(p.getDetail()).contains("{result}");
        assertThat(p.getApprovalDetail()).contains("{approval}");
        assertThat(p.getOmittedLine()).contains("{count}");
        for (ToolLedger.Status status : ToolLedger.Status.values()) {
            assertThat(p.statusLabel(status)).as(status.name()).isNotBlank();
        }
        assertThat(p.statusLabel(ToolLedger.Status.AWAITING_APPROVAL)).containsIgnoringCase("awaiting human approval");
    }

    @Test
    void parityBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        ChatParityProperties fromYml = binderOverApplicationYml().bind("app.chat.parity", ChatParityProperties.class).get();
        ChatParityProperties code = new ChatParityProperties();

        assertThat(fromYml.getFastBufferingTriggers()).isEqualTo(code.getFastBufferingTriggers())
            .isEqualTo(EnumSet.of(FastBufferTrigger.GENERATION_TOOLS, FastBufferTrigger.MCP_TOOLS));
        assertThat(fromYml.getVerifierMaxEvidenceChars()).isEqualTo(code.getVerifierMaxEvidenceChars()).isEqualTo(12000);
        assertThat(fromYml.getVerifierMinRevisionRatio()).isEqualTo(code.getVerifierMinRevisionRatio()).isEqualTo(0.3);
        assertThat(fromYml.getVerifierRatioAppliesAboveChars()).isEqualTo(code.getVerifierRatioAppliesAboveChars()).isEqualTo(400);
    }

    @Test
    void cacheBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        ChatCacheProperties fromYml = binderOverApplicationYml().bind("app.chat.cache", ChatCacheProperties.class).get();
        ChatCacheProperties code = new ChatCacheProperties();

        assertThat(fromYml.isEligibleWithTools()).isEqualTo(code.isEligibleWithTools()).isFalse();
        assertThat(fromYml.isKeyOnConversation()).isEqualTo(code.isKeyOnConversation()).isTrue();
        assertThat(fromYml.getContextTurns()).isEqualTo(code.getContextTurns()).isEqualTo(2);
    }

    /** The @Value defaults on ToolCallRecovery's constructor are the same values the yml ships. */
    @Test
    void textCallRecoveryAnnotationDefaultsEqualTheYml() throws Exception {
        StandardEnvironment env = environmentOverApplicationYml();
        PropertySourcesPlaceholdersResolver resolver = new PropertySourcesPlaceholdersResolver(env);
        Constructor<?> spring = Arrays.stream(ToolCallRecovery.class.getConstructors())
            .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
            .findFirst().orElseThrow();
        Pattern placeholder = Pattern.compile("\\$\\{(app\\.chat\\.tools\\.text-call-recovery\\.[a-z-]+):(.*)}");
        int checked = 0;
        for (Parameter parameter : spring.getParameters()) {
            Value value = parameter.getAnnotation(Value.class);
            if (value == null) {
                continue;
            }
            Matcher m = placeholder.matcher(value.value());
            assertThat(m.matches()).as(value.value()).isTrue();
            Object inYml = resolver.resolvePlaceholders("${" + m.group(1) + "}");
            assertThat(String.valueOf(inYml)).as(m.group(1)).isEqualTo(m.group(2));
            checked++;
        }
        assertThat(checked).isEqualTo(7);
    }
}
