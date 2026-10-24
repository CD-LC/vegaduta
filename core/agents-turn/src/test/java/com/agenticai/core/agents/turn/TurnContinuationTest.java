package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.TurnContinuation.Decision;
import com.agenticai.core.agents.turn.TurnContinuation.Signals;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TurnContinuationTest {

    private final ContinuationProperties properties = new ContinuationProperties();
    private final TurnContinuation continuation = new TurnContinuation(properties);

    private boolean unfinished(String reply) {
        return continuation.looksUnfinished(reply);
    }

    private Decision decide(String reply, boolean weak, boolean parked, int passes, int toolCalls) {
        return continuation.decide(new Signals(reply, weak, parked, passes, toolCalls));
    }

    // ------------------------------------------------------------------------- the wording

    @Test
    void announcedButUndoneStepIsUnfinished() {
        assertThat(unfinished("Good. Let me create the pom.xml file:")).isTrue();
        assertThat(unfinished("I will check what files exist and then create the next files.")).isTrue();
    }

    @Test
    void questionsAndSummariesAreNotUnfinished() {
        assertThat(unfinished("Which database should I use?")).isFalse();
        assertThat(unfinished("Shall I go ahead and approve the plan I will run next?")).isFalse();
        assertThat(unfinished("All done. The project builds and you can now run it.")).isFalse();
        assertThat(unfinished("   ")).isFalse();
        assertThat(unfinished(null)).isFalse();
    }

    /**
     * The old rule exempted ANY reply containing "approval"/"approve" - to cover a turn parked on a
     * human approval - so a reply that merely mentioned one was never nudged however clearly it
     * stopped at an announced step. A parked turn is recognised by the guard's explicit record
     * ({@code TurnApprovalState#isParked}), and the wording alone decides everything else.
     */
    @Test
    void mentioningApprovalDoesNotExemptAReplyThatStopsAtAnAnnouncedStep() {
        assertThat(unfinished("Now that you approved it earlier, I will run the next step:")).isTrue();
        assertThat(unfinished("The approval went through. Let me write the report.")).isTrue();
        assertThat(unfinished("I'll wait: the shell command needs your approval.")).isTrue();
    }

    /** The live incident the buffered depths' old announce check was written for. */
    @Test
    void aReplyThatPromisesWorkAndAsksForAMomentIsUnfinished() {
        assertThat(unfinished(
                        "Today is June 17, 2024. I will now retrieve all the latest Bollywood and Indian cinema news"
                                + " published today from reliable sources and compile a detailed report for you."
                                + " Please give me a moment."))
                .isTrue();
        assertThat(unfinished("One moment.")).isTrue();
    }

    @Test
    void anOfferOfFurtherHelpIsAnOrdinaryClosingNotAnAnnouncedStep() {
        assertThat(unfinished("I created the file. Let me know if you need anything else.")).isFalse();
        assertThat(unfinished("Done with the report. Feel free to ask if you want changes.")).isFalse();
        assertThat(unfinished("Here is the answer. Let us know whether it helps.")).isFalse();
    }

    @Test
    void onlyTheLastSentenceCanAnnounceAStep() {
        assertThat(unfinished("I will summarise the result below. The file is written and the build is green."))
                .isFalse();
        assertThat(unfinished("The file is written and the build is green.\nNow I will run the tests.")).isTrue();
    }

    @Test
    void aTypographicApostropheIsRecognisedLikeAStraightOne() {
        assertThat(unfinished("The file is written. Let’s run the build.")).isTrue();
        assertThat(unfinished("The file is written. Now I’ll run the build.")).isTrue();
    }

    @Test
    void aReplyThatEndsWhereAListWasAboutToStartIsUnfinished() {
        assertThat(unfinished("Here are the steps:")).isTrue();
        assertThat(unfinished("Here are the steps：")).isTrue();
    }

    @Test
    void aQuestionInAnyOfTheQuestionMarkStylesIsAskingNotAnnouncing() {
        assertThat(unfinished("Let me know which one？")).isFalse();
        assertThat(unfinished("هل تريد أن أبدأ؟")).isFalse();
    }

    // ---------------------------------------------------------------- other languages are configuration

    @Test
    void aLanguageIsAddedByConfigurationAndPicksItsOwnNudge() {
        Map<String, String> intent = new HashMap<>(properties.getIntent());
        intent.put("es", "\\b(voy a|déjame|ahora voy a)\\b");
        properties.setIntent(intent);
        Map<String, String> nudge = new HashMap<>(properties.getNudge());
        nudge.put("es", "Te detuviste antes de terminar. Sigue ahora y usa las herramientas.");
        properties.setNudge(nudge);
        TurnContinuation configured = new TurnContinuation(properties);

        assertThat(configured.unfinishedLocale("Archivo creado. Ahora voy a ejecutar la compilación.")).isEqualTo("es");
        assertThat(configured.unfinishedLocale("Let me run the build.")).isEqualTo("en");
        // the deprecated nudge.<lang> property seeds the platform default of the registry key; the policy asks the resolver
        ResolvedDirectives prompts =
                PromptDirectiveSettings.forTest(
                                new com.agenticai.core.prompts.DefaultPromptResolver(
                                        com.agenticai.core.prompts.PromptResolver.defaultsOnly().catalog(),
                                        null,
                                        new com.agenticai.core.prompts.PromptProperties(),
                                        java.util.List.of(PromptPropertyAliases.continueUnfinished(properties))))
                        .forTurn(java.time.LocalDate.of(2026, 10, 22), null);
        assertThat(configured.nudge("es", prompts)).startsWith("Te detuviste");
        assertThat(configured.nudge("en", prompts)).startsWith("You stopped before the task was finished");
        assertThat(configured.nudge("xx", prompts)).as("a language with no nudge falls back to the default one").startsWith("You stopped");
    }

    @Test
    void theTailThatIsReadIsConfigurable() {
        properties.setTailChars(10);

        assertThat(new TurnContinuation(properties).looksUnfinished("Let me create the file and then go on to the next step now")).isFalse();
    }

    // ------------------------------------------------------------------ the decision and its signals

    @Test
    void aFrontierModelsAnnouncementIsLeftAloneByDefault() {
        Decision d = decide("Let me create the pom.xml:", false, false, 0, 1);

        assertThat(d.proceed()).isFalse();
        assertThat(d.reason()).contains("does not apply to this model");
    }

    @Test
    void aWeakToolCallerIsContinuedWhenItsReplyStopsAtAnAnnouncedStep() {
        Decision d = decide("Created. Now let me run it:", true, false, 0, 1);

        assertThat(d.proceed()).isTrue();
        assertThat(d.locale()).isEqualTo("en");
    }

    @Test
    void allModelsScopeAppliesTheWordingCheckToEveryModel() {
        properties.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS);

        assertThat(new TurnContinuation(properties).decide(new Signals("Let me create the file:", false, false, 0, 0)).proceed()).isTrue();
    }

    @Test
    void theWordingCheckCanBeSwitchedOffEntirely() {
        properties.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS);
        properties.setHeuristicEnabled(false);

        assertThat(new TurnContinuation(properties).decide(new Signals("Let me create the file:", true, false, 0, 0)).proceed()).isFalse();
    }

    @Test
    void aTurnParkedOnAnApprovalIsNeverContinuedWhateverTheReplySays() {
        Decision d = decide("Let me run it:", true, true, 0, 0);

        assertThat(d.proceed()).isFalse();
        assertThat(d.reason()).contains("parked");
    }

    @Test
    void aPassAfterTheFirstMustHaveCalledATool() {
        assertThat(decide("Now let me run it:", true, false, 1, 0).proceed()).isFalse();
        assertThat(decide("Now let me run it:", true, false, 1, 2).proceed()).isTrue();
    }

    @Test
    void theFirstPassNeedsNoToolCallBecauseAnnouncingWithoutActingIsTheFailure() {
        assertThat(decide("Let me create the file:", true, false, 0, 0).proceed()).isTrue();
    }
}
