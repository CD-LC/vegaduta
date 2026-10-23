package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptDefaultAlias;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The prompt registry is the one mechanism for the nudge texts. The deprecated {@code app.chat.continuation.nudge.<lang>},
 * {@code app.chat.finish.continue-nudge} / {@code retry-tool-call-nudge} and {@code app.chat.escalation.low-confidence-markers}
 * properties only seed the PLATFORM default of their key: below every stored override, above the shipped file, logged when a
 * value was changed, and never read by a call site.
 */
class PromptPropertyAliasTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);
    private static final PromptCatalog CATALOG = PromptResolver.defaultsOnly().catalog();

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final ContinuationProperties continuation = new ContinuationProperties();
    private final ChatFinishProperties finish = new ChatFinishProperties();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        TenantContext.set(TenantContext.of("tenant-a", "owner", Set.of()));
        logger = (Logger) LoggerFactory.getLogger(PromptPropertyAliases.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        TenantContext.clear();
    }

    private DefaultPromptResolver resolver(PromptCatalog catalog, PromptDefaultAlias... aliases) {
        return new DefaultPromptResolver(catalog, store, new PromptProperties(), List.of(aliases));
    }

    private ResolvedDirectives directives(DefaultPromptResolver resolver, UUID agent) {
        return PromptDirectiveSettings.forTest(resolver).forTurn(DAY, agent);
    }

    private void override(DefaultPromptResolver resolver, PromptScope scope, String key, String text) {
        store.put(
                new PromptOverride(
                        scope, scope == PromptScope.AGENT ? AGENT.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
    }

    private List<String> warnings() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    private static String shipped(String key) {
        return CATALOG.defaultText(key);
    }

    // ---- precedence: agent > tenant > platform override > property alias > shipped file ----

    @Test
    void aTextThatIsNotSetAnywhereIsTheShippedFile() {
        DefaultPromptResolver resolver =
                resolver(CATALOG, PromptPropertyAliases.continueUnfinished(continuation), PromptPropertyAliases.cutOff(finish));

        assertThat(directives(resolver, AGENT).text(NudgeTexts.CONTINUE_UNFINISHED)).isEqualTo(shipped(NudgeTexts.CONTINUE_UNFINISHED));
        assertThat(directives(resolver, AGENT).text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo(shipped(NudgeTexts.REPLY_CUT_OFF));
        assertThat(warnings()).as("a property that still holds the shipped text is not an alias").isEmpty();
    }

    @Test
    void thePropertyIsThePlatformDefaultAndEveryStoredOverrideBeatsIt() {
        finish.setContinueNudge("From the property.");
        DefaultPromptResolver resolver = resolver(CATALOG, PromptPropertyAliases.cutOff(finish));
        UUID other = UUID.randomUUID();

        assertThat(directives(resolver, AGENT).text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo("From the property.");

        override(resolver, PromptScope.PLATFORM, NudgeTexts.REPLY_CUT_OFF, "From the platform.");
        assertThat(directives(resolver, AGENT).text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo("From the platform.");

        override(resolver, PromptScope.TENANT, NudgeTexts.REPLY_CUT_OFF, "From the tenant.");
        assertThat(directives(resolver, AGENT).text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo("From the tenant.");
        assertThat(directives(resolver, other).text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo("From the tenant.");

        override(resolver, PromptScope.AGENT, NudgeTexts.REPLY_CUT_OFF, "From the agent.");
        assertThat(directives(resolver, AGENT).text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo("From the agent.");
        assertThat(directives(resolver, other).text(NudgeTexts.REPLY_CUT_OFF)).as("the agent's text is its own").isEqualTo("From the tenant.");
    }

    @Test
    void theRetryNudgePropertyAndTheContinuationPropertyEachSeedOnlyTheirOwnKey() {
        finish.setRetryToolCallNudge("Try the tool again.");
        continuation.setNudge(Map.of("en", "Go on."));
        DefaultPromptResolver resolver =
                resolver(
                        CATALOG,
                        PromptPropertyAliases.continueUnfinished(continuation),
                        PromptPropertyAliases.cutOff(finish),
                        PromptPropertyAliases.retryToolCall(finish));
        ResolvedDirectives prompts = directives(resolver, AGENT);

        assertThat(prompts.text(NudgeTexts.RETRY_TOOL_CALL)).isEqualTo("Try the tool again.");
        assertThat(prompts.text(NudgeTexts.CONTINUE_UNFINISHED)).isEqualTo("Go on.");
        assertThat(prompts.text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo(shipped(NudgeTexts.REPLY_CUT_OFF));
    }

    @Test
    void aBlankPropertyIsIgnoredNotSentAsAnEmptyNudge() {
        finish.setContinueNudge("   ");
        DefaultPromptResolver resolver = resolver(CATALOG, PromptPropertyAliases.cutOff(finish));

        assertThat(directives(resolver, AGENT).text(NudgeTexts.REPLY_CUT_OFF)).isEqualTo(shipped(NudgeTexts.REPLY_CUT_OFF));
        assertThat(warnings()).anyMatch(w -> w.contains("app.chat.finish.continue-nudge") && w.contains("blank"));
    }

    // ---- the deprecation warning ----

    @Test
    void aChangedPropertyIsLoggedWithTheKeyToEditInstead() {
        finish.setContinueNudge("Changed.");
        finish.setRetryToolCallNudge("Changed too.");
        continuation.setNudge(Map.of("en", "Changed three.", "es", "Cambiado."));

        PromptPropertyAliases.cutOff(finish);
        PromptPropertyAliases.retryToolCall(finish);
        PromptPropertyAliases.continueUnfinished(continuation);
        PromptPropertyAliases.lowConfidenceMarkers("no idea");

        List<String> warnings = warnings();
        assertThat(warnings).anyMatch(w -> w.contains("app.chat.finish.continue-nudge") && w.contains("'nudge.reply_cut_off'") && w.contains("deprecated"));
        assertThat(warnings).anyMatch(w -> w.contains("app.chat.finish.retry-tool-call-nudge") && w.contains("'nudge.retry_tool_call'"));
        assertThat(warnings).anyMatch(w -> w.contains("app.chat.continuation.nudge.en") && w.contains("'nudge.continue_unfinished'"));
        assertThat(warnings).anyMatch(w -> w.contains("app.chat.continuation.nudge.es"));
        assertThat(warnings)
                .anyMatch(w -> w.contains("app.chat.escalation.low-confidence-markers") && w.contains("'detector.escalation.low_confidence_markers'"));
    }

    @Test
    void theShippedConfigurationLogsNothing() {
        PromptPropertyAliases.cutOff(new ChatFinishProperties());
        PromptPropertyAliases.retryToolCall(new ChatFinishProperties());
        PromptPropertyAliases.continueUnfinished(new ContinuationProperties());
        PromptPropertyAliases.lowConfidenceMarkers("");

        assertThat(warnings()).isEmpty();
    }

    // ---- locale selection ----

    @Test
    void aLanguageAddedByThePropertyPicksItsOwnTextAndAnUnknownLanguageFallsBackToTheDefaultOne() {
        continuation.setNudge(Map.of("en", shipped(NudgeTexts.CONTINUE_UNFINISHED), "es", "Sigue ahora y usa las herramientas."));
        DefaultPromptResolver resolver = resolver(CATALOG, PromptPropertyAliases.continueUnfinished(continuation));
        ResolvedDirectives prompts = directives(resolver, AGENT);
        TurnContinuation policy = new TurnContinuation(continuation);

        assertThat(policy.nudge("es", prompts)).isEqualTo("Sigue ahora y usa las herramientas.");
        assertThat(policy.nudge("en", prompts)).isEqualTo(shipped(NudgeTexts.CONTINUE_UNFINISHED));
        assertThat(policy.nudge("xx", prompts)).isEqualTo(shipped(NudgeTexts.CONTINUE_UNFINISHED));
        assertThat(policy.nudge("es", null)).as("no registry view: the shipped text").isEqualTo(shipped(NudgeTexts.CONTINUE_UNFINISHED));
    }

    @Test
    void aChangedDefaultLanguageNudgeIsTheFallbackOfEveryLanguageWithoutOne() {
        continuation.setNudge(Map.of("en", "Changed in English."));
        DefaultPromptResolver resolver = resolver(CATALOG, PromptPropertyAliases.continueUnfinished(continuation));
        TurnContinuation policy = new TurnContinuation(continuation);
        ResolvedDirectives prompts = directives(resolver, AGENT);

        assertThat(policy.nudge("en", prompts)).isEqualTo("Changed in English.");
        assertThat(policy.nudge("es", prompts)).isEqualTo("Changed in English.");
    }

    @Test
    void aShippedTranslationIsUsedForItsLanguageAndAnOverrideOfTheDefaultLanguageStillBeatsIt() {
        String front =
                "---\ntitle: Nudge\ndescription: test\nrisk: STEERS_EVERY_TURN\nmaxLength: 4000\n---\n";
        PromptCatalog catalog =
                PromptCatalog.fromSources(
                        Map.of(
                                "prompts/en/nudge.continue_unfinished.md", front + "Carry on.\n",
                                "prompts/de/nudge.continue_unfinished.md", "Mach weiter.\n"),
                        "en",
                        8000);
        DefaultPromptResolver resolver = resolver(catalog);
        ResolvedDirectives prompts = directives(resolver, AGENT);
        TurnContinuation policy = new TurnContinuation(continuation);

        assertThat(policy.nudge("de", prompts)).isEqualTo("Mach weiter.");
        assertThat(policy.nudge("en", prompts)).isEqualTo("Carry on.");
        assertThat(policy.nudge("fr", prompts)).isEqualTo("Carry on.");

        override(resolver, PromptScope.TENANT, NudgeTexts.CONTINUE_UNFINISHED, "Weitermachen!");
        assertThat(policy.nudge("de", directives(resolver, AGENT))).as("the stored text is read for every language").isEqualTo("Weitermachen!");
    }

    // ---- the registry markers ----

    @Test
    void theMarkersPropertySeedsTheListAndIsLowercasedOneMarkerPerLine() {
        DefaultPromptResolver resolver = resolver(CATALOG, PromptPropertyAliases.lowConfidenceMarkers(" No Idea , maybe ,, "));

        assertThat(directives(resolver, null).text("detector.escalation.low_confidence_markers")).isEqualTo("no idea\nmaybe");
        assertThat(directives(resolver(CATALOG, PromptPropertyAliases.lowConfidenceMarkers("  ")), null).text("detector.escalation.low_confidence_markers"))
                .isEqualTo(shipped("detector.escalation.low_confidence_markers"));
    }

    // ---- no call site reads the properties any more ----

    @Test
    void onlyTheAliasAndThePropertiesThemselvesReadTheDeprecatedProperties() throws IOException {
        Path root = Path.of("src/main/java");
        Set<String> allowed = Set.of("PromptPropertyAliases.java", "PromptPropertyAliasConfiguration.java", "ContinuationProperties.java", "ChatFinishProperties.java");
        List<String> offenders;
        try (Stream<Path> files = Files.walk(root)) {
            offenders =
                    files.filter(f -> f.toString().endsWith(".java"))
                            .filter(f -> !allowed.contains(f.getFileName().toString()))
                            .filter(
                                    f -> {
                                        try {
                                            String text = Files.readString(f);
                                            return text.contains("getContinueNudge")
                                                    || text.contains("getRetryToolCallNudge")
                                                    || text.contains(".getNudge(")
                                                    || text.contains("low-confidence-markers:");
                                        } catch (IOException e) {
                                            throw new java.io.UncheckedIOException(e);
                                        }
                                    })
                            .map(Path::toString)
                            .toList();
        }
        assertThat(offenders).as("a call site must read the resolver, not the deprecated property").isEmpty();
    }
}
