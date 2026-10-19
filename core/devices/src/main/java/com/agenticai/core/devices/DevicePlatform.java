package com.agenticai.core.devices;

/**
 * Which SDK/agent client paired this device - Android (the
 * AccessibilityService-based automation client, buildable in Android Studio
 * today), iOS (a Swift package skeleton only, constrained by App Store
 * policy against exactly this kind of on-device automation - see the iOS
 * module's own constraints doc for why it can't reach feature parity while
 * staying App-Store-distributable), or Desktop (a downloadable CLI daemon -
 * Node.js, the new top-level {@code desktop-agent/} package being built in a
 * parallel workstream - that runs local LLM inference on the customer's own
 * machine using whatever model the user chooses, not a curated catalog like
 * the browser's WebGPU-constrained edge tier, and executes queued {@link
 * DeviceCommand}s the same way the mobile clients do).
 */
public enum DevicePlatform {
    ANDROID,
    IOS,
    DESKTOP,
    /**
     * The /device page in the console - any browser (phone or laptop) pairs
     * with a code and acts as a device node, declaring only the capabilities
     * the platform can actually grant (camera, geolocation, notifications,
     * clipboard, one-shot screen share).
     */
    WEB
}
