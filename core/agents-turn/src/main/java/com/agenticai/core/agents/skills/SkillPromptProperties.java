package com.agenticai.core.agents.skills;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.skills.prompt.*}: how large the skill index in the system prompt may be and how long the sticky
 * "skills stay on" memory is. These were constants of {@link SkillPromptBlock} (60 skills, 9,000 characters,
 * 2,000 characters of headroom for bare names, 30 description words, 6 remembered turns, 2,000 tracked
 * sessions); the values here are those numbers.
 *
 * <p>{@link SkillPromptBlock} is a static helper called from other modules, so the bound instance is also
 * published through {@link #activate} by {@link SkillPromptConfiguration}; a caller with no Spring context
 * sees the shipped defaults. {@code application.yml} repeats the defaults and {@code
 * SkillPromptDefaultsTest} pins the two together.
 */
@ConfigurationProperties(prefix = "app.skills.prompt")
public class SkillPromptProperties {

    private static volatile SkillPromptProperties active = new SkillPromptProperties();

    /** Hard ceiling on skills listed in the prompt; the rest are counted, not listed. */
    private int maxSkills = 60;

    /** Character budget of the whole index (about 2,300 tokens); entries keep their description while it fits. */
    private int maxBlockChars = 9_000;

    /** Extra room, past the budget, that bare names may still use before the list is cut. */
    private int namesOnlyHeadroom = 2_000;

    /** Words of a skill's description kept in the index. */
    private int maxDescriptionWords = 30;

    /** Earlier user messages remembered per session by the sticky gate. */
    private int historyTurns = 6;

    /** Sessions the sticky gate tracks before the least recently used are forgotten. */
    private int maxTrackedSessions = 2_000;

    /**
     * What a tenant's {@code skills.prompt.*} settings do to the bound properties, applied on the calling thread (the
     * tenant is the thread's); null = nothing. See {@link SkillPromptSettings}.
     */
    private static volatile java.util.function.UnaryOperator<SkillPromptProperties> tenantOverlay;

    /**
     * The settings {@link SkillPromptBlock} uses when it is not handed any: the bound properties, with the calling
     * thread's tenant's {@code skills.prompt.*} settings applied when there are any.
     */
    public static SkillPromptProperties active() {
        SkillPromptProperties bound = active;
        java.util.function.UnaryOperator<SkillPromptProperties> overlay = tenantOverlay;
        if (overlay == null) {
            return bound;
        }
        try {
            SkillPromptProperties effective = overlay.apply(bound);
            return effective == null ? bound : effective;
        } catch (RuntimeException e) {
            // A settings problem never changes what a prompt is built from.
            return bound;
        }
    }

    /** The bound properties as the platform has them, without any tenant's settings: the catalog's platform value. */
    public static SkillPromptProperties platform() {
        return active;
    }

    /** Publishes the bound settings to the static helper; {@code null} restores the defaults. */
    public static void activate(SkillPromptProperties properties) {
        active = properties == null ? new SkillPromptProperties() : properties;
    }

    /** Installs (or, with null, removes) the tenant overlay. */
    public static void useTenantOverlay(java.util.function.UnaryOperator<SkillPromptProperties> overlay) {
        tenantOverlay = overlay;
    }

    /** A copy with the two sizes a tenant may set replaced; every other value is kept. */
    public SkillPromptProperties with(int maxSkills, int maxBlockChars) {
        SkillPromptProperties c = new SkillPromptProperties();
        c.setMaxSkills(maxSkills);
        c.setMaxBlockChars(maxBlockChars);
        c.setNamesOnlyHeadroom(namesOnlyHeadroom);
        c.setMaxDescriptionWords(maxDescriptionWords);
        c.setHistoryTurns(historyTurns);
        c.setMaxTrackedSessions(maxTrackedSessions);
        return c;
    }

    public int getMaxSkills() {
        return maxSkills;
    }

    public void setMaxSkills(int maxSkills) {
        this.maxSkills = Math.max(1, maxSkills);
    }

    public int getMaxBlockChars() {
        return maxBlockChars;
    }

    public void setMaxBlockChars(int maxBlockChars) {
        this.maxBlockChars = Math.max(0, maxBlockChars);
    }

    public int getNamesOnlyHeadroom() {
        return namesOnlyHeadroom;
    }

    public void setNamesOnlyHeadroom(int namesOnlyHeadroom) {
        this.namesOnlyHeadroom = Math.max(0, namesOnlyHeadroom);
    }

    public int getMaxDescriptionWords() {
        return maxDescriptionWords;
    }

    public void setMaxDescriptionWords(int maxDescriptionWords) {
        this.maxDescriptionWords = Math.max(1, maxDescriptionWords);
    }

    public int getHistoryTurns() {
        return historyTurns;
    }

    public void setHistoryTurns(int historyTurns) {
        this.historyTurns = Math.max(1, historyTurns);
    }

    public int getMaxTrackedSessions() {
        return maxTrackedSessions;
    }

    public void setMaxTrackedSessions(int maxTrackedSessions) {
        this.maxTrackedSessions = Math.max(1, maxTrackedSessions);
    }
}
