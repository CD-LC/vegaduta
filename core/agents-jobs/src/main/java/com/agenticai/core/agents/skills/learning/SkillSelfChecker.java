package com.agenticai.core.agents.skills.learning;

import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Replays a drafted skill against the turn it was drafted from, before the person ever sees it:
 * one comparison call through the lab completion path (the agent's own model, usage-limited and
 * attributed like any lab run) asks whether following the skill would have produced an answer at
 * least as good as what actually happened.
 *
 * <p>It judges rather than re-runs the turn: a tool-less lab call cannot redo tool work, so a
 * re-run would be graded unfairly against the original. The judge sees the (scrubbed) request,
 * the answer that was given and the draft, and compares what the draft would have steered.
 *
 * <p>Runs only on the learning worker thread (called from {@code SkillLearningService}, which
 * already carries the tenant) and only when {@code app.skills.learning.self-check.enabled=true};
 * otherwise the bean does not exist. It never throws: no verdict means "unchecked".
 */
@Component
@ConditionalOnProperty(name = "app.skills.learning.self-check.enabled", havingValue = "true")
public class SkillSelfChecker {

    private static final Logger log = LoggerFactory.getLogger(SkillSelfChecker.class);

    static final int REQUEST_CHARS = 800;
    static final int ANSWER_CHARS = 1_500;
    static final int INSTRUCTION_CHARS = 3_000;

    /** Registry key of the system prompt (default text: {@code prompts/en/learning.skill_self_check.md}). */
    static final String PROMPT_KEY = "learning.skill_self_check";

    /**
     * The shipped wording of {@link #PROMPT_KEY}, for tests and docs only: a call resolves the key through
     * the registry so a tenant or agent override applies.
     */
    static final String SYSTEM_PROMPT = PromptResolver.defaultsOnly().catalog().defaultText(PROMPT_KEY);

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of {@link #PROMPT_KEY} applies; absent = shipped text. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final AgentLabCompletionService lab;
    private final SkillDraftScrubber scrubber;

    public SkillSelfChecker(
            AgentLabCompletionService lab, ObjectProvider<SkillDraftScrubber> platformScrubber) {
        this.lab = lab;
        SkillDraftScrubber platform = platformScrubber.getIfAvailable();
        this.scrubber =
                platform == null
                        ? SkillDraftScrubber.basic()
                        : text -> platform.scrub(SkillDraftScrubber.basic().scrub(text));
    }

    /**
     * @return the verdict, or empty when the call failed, hit a usage limit or the reply could not
     *     be read (the caller then queues the draft unchecked)
     */
    public Optional<SkillSelfCheckVerdict> check(
            UUID agentId,
            String sessionId,
            String userMessage,
            String answer,
            String skillName,
            String skillInstructions,
            String subject) {
        String reply;
        try {
            reply =
                    lab.complete(
                            new LabCompletionRequest(
                                    agentId,
                                    prompts.text(PROMPT_KEY, agentId),
                                    List.of(),
                                    buildMessage(userMessage, answer, skillName, skillInstructions),
                                    0.0,
                                    300,
                                    subject,
                                    "skill-self-check:" + sessionId));
        } catch (RuntimeException e) {
            if (AgentLabCompletionService.isLimitFailure(e)) {
                log.debug("Skill self-check skipped for agent {}: {}", agentId, e.getMessage());
            } else {
                log.warn("Skill self-check call failed for agent {}: {}", agentId, e.getMessage());
            }
            return Optional.empty();
        }
        return SkillSelfCheckVerdict.parse(reply);
    }

    String buildMessage(String userMessage, String answer, String skillName, String instructions) {
        String body =
                "Untrusted data\n\nUser request: "
                        + field(userMessage, REQUEST_CHARS, true)
                        + "\n\nAnswer that was given: "
                        + field(answer, ANSWER_CHARS, true)
                        + "\n\nDraft skill '"
                        + field(skillName, 60, true)
                        + "':\n"
                        + field(instructions, INSTRUCTION_CHARS, false);
        // Angle brackets are stripped so the data cannot imitate the message's own markup.
        return body.replace('<', '(').replace('>', ')');
    }

    /** Cut generously, scrubbed WHOLE, then cut to size: a secret straddling the cut never survives. */
    private String field(String text, int max, boolean oneLine) {
        return clip(scrubber.scrub(clip(text, max * 4, oneLine)), max, oneLine);
    }

    private static String clip(String text, int max, boolean oneLine) {
        if (text == null) {
            return "";
        }
        String t = oneLine ? text.replaceAll("\\s+", " ").strip() : text.strip();
        return t.length() <= max ? t : t.substring(0, max) + "...";
    }
}
