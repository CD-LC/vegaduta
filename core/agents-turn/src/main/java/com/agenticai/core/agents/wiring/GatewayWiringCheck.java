package com.agenticai.core.agents.wiring;

import com.agenticai.core.agents.skills.LessonLearningGateway;
import com.agenticai.core.agents.skills.PreferenceSignalGateway;
import com.agenticai.core.agents.skills.RoutineObservationGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway;
import com.agenticai.core.agents.turnjournal.TurnJournalGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Checks, once the application is ready, that every process-wide {@code *Gateway} a switched-on feature needs was
 * actually installed.
 *
 * <p>The chat turn is assembled from plain objects that are not beans, so it reaches the learning loops and the turn
 * journal through static holders ({@code SkillLearningGateway}, {@code TurnJournalGateway}, ...) that the feature's own
 * bean installs when it starts. A holder nobody installed is a silent no-op by design, which is also how a
 * mis-assembled deployment (the flag is on, the bean is missing: a profile, a module left out, a changed condition)
 * ends up with a feature that is switched on and does nothing, with no log line to say so. This turns that into an
 * ERROR naming the feature, the flag and the holder, or - with {@code app.wiring.strict-gateways=true} - into a failed
 * start.
 *
 * <p>The features and the flags that switch them on are listed in {@link #standardFeatures()}; a test pins that every
 * holder with an {@code install} method is covered.
 */
@Component
public class GatewayWiringCheck {

    private static final Logger log = LoggerFactory.getLogger(GatewayWiringCheck.class);

    /**
     * One feature that reaches the turn through a static gateway.
     *
     * @param name the feature, for the message
     * @param gateway the holder's simple class name, for the message
     * @param properties the flags that must all be {@code true} for the feature to be on (the same list the feature's
     *     bean is conditional on)
     * @param installed whether the holder has what the feature needs
     */
    public record Feature(String name, String gateway, List<String> properties, BooleanSupplier installed) {}

    private final Environment environment;
    private final boolean strict;
    private final List<Feature> features;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayWiringCheck(
            Environment environment, @Value("${app.wiring.strict-gateways:false}") boolean strict) {
        this(environment, strict, standardFeatures());
    }

    GatewayWiringCheck(Environment environment, boolean strict, List<Feature> features) {
        this.environment = environment;
        this.strict = strict;
        this.features = List.copyOf(features);
    }

    /** Every gateway a feature of the chat turn depends on, with the flags that switch the feature on. */
    public static List<Feature> standardFeatures() {
        return List.of(
                new Feature(
                        "turn journal",
                        "TurnJournalGateway",
                        List.of("app.turn-journal.enabled"),
                        TurnJournalGateway::enabled),
                new Feature(
                        "skill learning",
                        "SkillLearningGateway",
                        List.of("app.skills.learning.enabled"),
                        SkillLearningGateway::installed),
                new Feature(
                        "routine learning",
                        "RoutineObservationGateway",
                        List.of("app.learning.proposals.enabled", "app.learning.routines.enabled"),
                        RoutineObservationGateway::installed),
                new Feature(
                        "preference learning",
                        "PreferenceSignalGateway",
                        List.of("app.learning.proposals.enabled", "app.learning.preferences.enabled"),
                        PreferenceSignalGateway::installed),
                new Feature(
                        "lesson learning",
                        "LessonLearningGateway",
                        List.of("app.learning.lessons.enabled", "app.learning.proposals.enabled"),
                        LessonLearningGateway::installed));
    }

    /** One message per feature that is switched on and whose gateway is not installed; empty when all is wired. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        for (Feature feature : features) {
            boolean on =
                    feature.properties().stream()
                            .allMatch(p -> environment.getProperty(p, Boolean.class, false));
            if (on && !feature.installed().getAsBoolean()) {
                problems.add(
                        "The "
                                + feature.name()
                                + " is switched on ("
                                + String.join(" and ", feature.properties())
                                + "=true) but "
                                + feature.gateway()
                                + " was never installed, so it does nothing. The bean that installs it is not in this"
                                + " application context (a profile, a missing module or a changed condition).");
            }
        }
        return problems;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        List<String> problems = problems();
        for (String problem : problems) {
            log.error("Gateway wiring: {}", problem);
        }
        if (strict && !problems.isEmpty()) {
            throw new IllegalStateException(
                    "app.wiring.strict-gateways=true and " + problems.size() + " gateway(s) are not wired: "
                            + String.join(" | ", problems));
        }
    }
}
