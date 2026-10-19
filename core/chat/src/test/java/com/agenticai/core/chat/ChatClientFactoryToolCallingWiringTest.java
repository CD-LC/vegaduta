package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

/**
 * The regression guard for how this whole problem happened. {@link
 * ArgumentSanitizingToolCallingManager} was wired into exactly one of the
 * thirteen chat-client factories in this package - the Sarvam one, because
 * Sarvam is where the incidents were diagnosed - and the omission was
 * invisible: nothing failed, nothing warned, and the twelve unprotected
 * providers (including {@link HostedLlmChatClientFactory}, the pool every
 * FREE-tier agent runs on) simply kept hard-failing a turn whenever a model
 * invented a tool name. A fourteenth factory added later would silently
 * reintroduce it.
 *
 * <p>So this asserts the property directly rather than trusting a reviewer to
 * notice: every {@code *ChatClientFactory} component in this package takes a
 * {@link ResilientToolCallingManagerFactory}. Declaring the collaborator is
 * what is checkable by reflection; that it is then passed to {@code
 * ChatModel.Builder#toolCallingManager} is not, since the resulting {@code
 * ChatClient} exposes nothing to inspect. That residual gap is small and
 * obvious - an unused constructor parameter reads as dead weight and trips
 * static analysis - whereas the gap this closes was neither.
 */
class ChatClientFactoryToolCallingWiringTest {

    private static final String PACKAGE = "com.agenticai.core.chat";

    /**
     * Factories whose {@code ChatModel} has no tool-calling seam to wire at
     * all, with the reason. Adding to this set should require the same
     * justification {@link AwsBedrockAgentChatClientFactory}'s javadoc gives:
     * the model cannot emit a tool call in the first place.
     */
    private static final Set<String> EXEMPT = Set.of(
        // AwsBedrockChatModel is hand-rolled over Bedrock's Converse API and
        // never sends toolConfig, so no tool call can come back to sanitize -
        // and it exposes no ToolCallingManager to accept one.
        "AwsBedrockAgentChatClientFactory"
    );

    @Test
    void everyToolCapableChatClientFactoryTakesTheSharedToolCallingManager() {
        Set<Class<?>> factories = chatClientFactories();
        Set<String> missing = new TreeSet<>();
        for (Class<?> factory : factories) {
            if (!EXEMPT.contains(factory.getSimpleName()) && !declaresToolCallingManagerFactory(factory)) {
                missing.add(factory.getSimpleName());
            }
        }

        // Guard the guard: a scan that silently found nothing would leave this
        // test passing forever while asserting nothing at all.
        assertTrue(
            factories.size() >= 13,
            "expected to scan every chat-client factory in " + PACKAGE + ", found only " + simpleNames(factories)
        );
        assertTrue(
            missing.isEmpty(),
            "These factories can still be killed mid-turn by a model's own malformed or invented tool call. "
                + "Take a ResilientToolCallingManagerFactory and pass create() to the ChatModel builder's "
                + "toolCallingManager(..), or add the class to EXEMPT with a reason: " + missing
        );
    }

    @Test
    void theExemptListNamesOnlyFactoriesThatStillExist() {
        Set<String> stale = new TreeSet<>(EXEMPT);
        stale.removeAll(simpleNames(chatClientFactories()));

        assertTrue(
            stale.isEmpty(),
            "EXEMPT lists factories that no longer exist - a renamed factory would otherwise carry its "
                + "exemption silently into a class that never earned it: " + stale
        );
    }

    private static Set<Class<?>> chatClientFactories() {
        // Deliberately matched by name rather than by `implements
        // AgentChatClientFactory`: HostedLlmChatClientFactory implements
        // nothing (the FREE-tier pool is a runtime pick, not a ProviderName
        // lookup - see its javadoc), and it is the single most important one
        // to cover.
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*ChatClientFactory$")));

        Set<Class<?>> factories = new LinkedHashSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(PACKAGE)) {
            factories.add(load(definition.getBeanClassName()));
        }
        return factories;
    }

    private static Set<String> simpleNames(Set<Class<?>> types) {
        Set<String> names = new TreeSet<>();
        for (Class<?> type : types) {
            names.add(type.getSimpleName());
        }
        return names;
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("scanned a class that will not load: " + className, e);
        }
    }

    private static boolean declaresToolCallingManagerFactory(Class<?> factory) {
        for (Constructor<?> constructor : factory.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                if (ResilientToolCallingManagerFactory.class.equals(parameter.getType())) {
                    return true;
                }
            }
        }
        return false;
    }
}
