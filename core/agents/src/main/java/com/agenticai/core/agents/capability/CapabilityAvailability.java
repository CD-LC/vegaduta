package com.agenticai.core.agents.capability;

/**
 * Whether a capability can meaningfully be turned on for an agent RIGHT NOW,
 * independent of its current on/off state - the server-resolved answer the
 * workbench used to have to piece together itself from a pile of
 * provider-specific probes (getSarvamCredential, getElevenLabsCredential,
 * sidecar health, OPENAI-active, ...). New providers plug their own check in
 * here instead of adding another frontend probe.
 */
public enum CapabilityAvailability {
    /** Ready to use the moment it is toggled on. */
    AVAILABLE,
    /**
     * The provider/integration exists but needs tenant setup first (a
     * credential, a template id, ...) - the toggle is still flippable (the
     * flag is simply inert until setup completes, same semantics the old
     * per-flag endpoints always had), but the UI should surface the
     * capability's {@code availabilityHint} so the tenant knows why nothing
     * happens yet.
     */
    NEEDS_SETUP,
    /**
     * Not wired on this deployment at all (the backing module/bean is
     * absent) - a "coming soon" surface, not a setup problem. The UI should
     * render the row disabled rather than offering a toggle that can never
     * do anything.
     */
    UNAVAILABLE
}
