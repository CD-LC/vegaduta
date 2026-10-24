package com.agenticai.core.agents.collab;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.agents.collaboration.*}: the bounds on the parallel fan-out tools, {@code delegate_parallel}
 * ({@link DelegateToTeamToolCallback}) and {@code run_subtasks} ({@link RunSubtasksToolCallback}). The two
 * classes each carried their own copy of the same constants (8 branches, 240 s, 4000 characters), so a change
 * to one silently left the other behind; there is one setting now.
 *
 * <p>Defaults are read from {@code /collab/collaboration.properties}; the same values are in {@code
 * application.yml}. Registered by {@link CollaborationConfiguration}.
 */
@ConfigurationProperties(prefix = "app.agents.collaboration")
public class CollaborationProperties {

    private static final String DEFAULTS_RESOURCE = "/collab/collaboration.properties";

    private static final Properties DEFAULTS = loadDefaults();

    /** Branches one call may start. */
    private int maxBranches = Integer.parseInt(DEFAULTS.getProperty("max-branches"));

    /** Wall-clock seconds one branch may run. */
    private long branchTimeoutSeconds = Long.parseLong(DEFAULTS.getProperty("branch-timeout-seconds"));

    /** Characters of one branch's answer handed back to the calling model. */
    private int maxAnswerChars = Integer.parseInt(DEFAULTS.getProperty("max-answer-chars"));

    /** Characters kept of a run_subtasks worker's role label. */
    private int maxRoleChars = Integer.parseInt(DEFAULTS.getProperty("max-role-chars"));

    private static Properties loadDefaults() {
        Properties defaults = new Properties();
        try (InputStream in = CollaborationProperties.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(DEFAULTS_RESOURCE + " is missing from the agents-interop module");
            }
            defaults.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return defaults;
    }

    public int getMaxBranches() {
        return maxBranches;
    }

    public void setMaxBranches(int maxBranches) {
        this.maxBranches = Math.max(1, maxBranches);
    }

    public long getBranchTimeoutSeconds() {
        return branchTimeoutSeconds;
    }

    public void setBranchTimeoutSeconds(long branchTimeoutSeconds) {
        this.branchTimeoutSeconds = Math.max(1, branchTimeoutSeconds);
    }

    public int getMaxAnswerChars() {
        return maxAnswerChars;
    }

    public void setMaxAnswerChars(int maxAnswerChars) {
        this.maxAnswerChars = Math.max(1, maxAnswerChars);
    }

    public int getMaxRoleChars() {
        return maxRoleChars;
    }

    public void setMaxRoleChars(int maxRoleChars) {
        this.maxRoleChars = Math.max(1, maxRoleChars);
    }
}
