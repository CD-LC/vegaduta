package com.agenticai.core.agents.tools;

import java.util.Set;

/**
 * The wire names of the {@code local_desktop_*} tools and {@code run_desktop_recipe}. The
 * implementations live in the agents-tools desktop package, but the names are kernel vocabulary:
 * governance ({@code AgentToolGovernance}) needs the gated set to pin them for approval, and
 * pulling it from the desktop package would close a tools -> tools.desktop -> tools package cycle.
 */
public final class DesktopToolNames {

    public static final String SCREEN = "local_desktop_screen";
    public static final String ELEMENTS = "local_desktop_elements";
    public static final String PRESS = "local_desktop_press";
    public static final String CLICK = "local_desktop_click";
    public static final String TYPE = "local_desktop_type";
    public static final String KEY = "local_desktop_key";
    public static final String OPEN = "local_desktop_open";
    public static final String ACTIVATE = "local_desktop_activate";
    public static final String RUN_RECIPE = "run_desktop_recipe";

    /**
     * The actuating tools - read-only ones (screen, elements, notify, clipboard) are never pinned.
     */
    public static final Set<String> GATED =
            Set.of(PRESS, CLICK, TYPE, KEY, OPEN, ACTIVATE, RUN_RECIPE);

    private DesktopToolNames() {}
}
