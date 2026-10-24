package com.agenticai.core.settings;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * A stored security value that is weaker than the one it would override (a row written around the API, or a platform value
 * tightened after the override was stored) is ignored at read time AND the operator is told, once, which key and which value.
 */
class ResolverWeakerValueWarningTest {

    private SettingsFixture f;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        f = new SettingsFixture();
        SettingsFixture.asAdmin();
        logger = (Logger) LoggerFactory.getLogger(DefaultSettingsResolver.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        TenantContext.clear();
    }

    private long warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("guardrails.toxicity.blockScore"))
                .count();
    }

    @Test
    void aWeakerStoredSecurityValueIsIgnoredAndWarnedAboutOnce() {
        f.store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "guardrails.toxicity.blockScore", 90, 1, "mallory", Instant.now()));

        for (int i = 0; i < 3; i++) {
            f.resolver.evictCurrentTenant();
            SettingValue v = f.resolver.get("guardrails.toxicity.blockScore", SettingContext.tenant());
            assertThat(v.value()).as("the platform's 60 applies, not the weaker 90").isEqualTo(60L);
            assertThat(v.source()).isNotEqualTo(SettingSource.TENANT);
        }

        assertThat(warnings()).as("one warning per key and value, not one per read").isEqualTo(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("weaker").contains("90").contains("60");
    }

    @Test
    void aStricterOrEqualStoredValueIsNotWarnedAbout() {
        f.store.saveCurrent(new SettingOverride(SettingScope.TENANT, "", "guardrails.toxicity.blockScore", 40, 1, "alice", Instant.now()));

        assertThat(f.resolver.get("guardrails.toxicity.blockScore", SettingContext.tenant()).value()).isEqualTo(40L);
        assertThat(warnings()).isZero();
    }

    @Test
    void aWeakerAgentValueIsIgnoredAndWarnedAboutToo() {
        f.failMode.set("closed");
        f.store.saveCurrent(new SettingOverride(
                SettingScope.AGENT, SettingsFixture.AGENT.toString(), "guardrails.failMode", "open", 1, "mallory", Instant.now()));

        SettingValue v = f.resolver.get("guardrails.failMode", SettingContext.forAgent(SettingsFixture.AGENT));

        assertThat(v.value()).isEqualTo("closed");
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("guardrails.failMode") && e.getFormattedMessage().contains("agent"));
    }
}
