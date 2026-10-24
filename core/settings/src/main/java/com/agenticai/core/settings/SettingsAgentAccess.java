package com.agenticai.core.settings;

import java.util.UUID;

/**
 * Port to the agents module: does the caller have access to this agent, in the calling thread's tenant? Each method
 * throws a 403 (no access) or 404 (no such agent in this tenant, which includes an agent of another tenant) and
 * returns normally on success.
 */
public interface SettingsAgentAccess {

    /** At least VIEWER on the agent. */
    void requireViewer(UUID agentId);

    /** At least EDITOR on the agent. */
    void requireEditor(UUID agentId);

    /**
     * The agent may be changed right now: it is not edit-locked (for example a free-tier agent past its allowance),
     * the same check every other after-creation change of an agent makes. Called for writes only, so a locked agent
     * can still be read and its history shown. Throws like {@link #requireEditor} when it may not.
     */
    void requireEditable(UUID agentId);
}
