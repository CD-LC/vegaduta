package com.agenticai.core.providers.addons;

/** What the API says an add-on is doing right now. {@link #wire()} is the JSON value. */
public enum AddonStatus {
    /** Not wanted and not running. */
    OFF("off"),
    /** Wanted; the host (VegaDuta Setup, or the command line) has not applied it yet. */
    ENABLE_REQUESTED("enable_requested"),
    /** The host is applying it now. */
    APPLYING("applying"),
    /** Wanted and running (reported by the host, or seen by core itself). */
    ON("on"),
    /** Switched off in the console but still running; waiting for the host to stop it. */
    DISABLE_REQUESTED("disable_requested"),
    /** Core is downloading the model. */
    PULLING("pulling"),
    /** The last attempt did not work; {@code message} says why. */
    FAILED("failed"),
    /** A planned add-on: listed, cannot be switched on. */
    UNAVAILABLE("unavailable");

    private final String wire;

    AddonStatus(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
