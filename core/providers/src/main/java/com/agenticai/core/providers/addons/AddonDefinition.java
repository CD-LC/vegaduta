package com.agenticai.core.providers.addons;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of {@code addons/catalog.json}: an optional capability a person can switch on. Pure data;
 * {@link AddonCatalog} parses and validates it, {@link AddonService} turns it plus the stored state
 * into what the API returns. The field names are the file's contract (and, for the shared subset, the
 * API's), so renaming one is a breaking change for the installer's copy of the catalog.
 *
 * @param sizeMb approximate disk space in MB the add-on needs once installed (an image, or the model
 *     weights); informational
 * @param minRamMb memory in MB the add-on's containers may use on top of the base stack (for a model:
 *     what the catalog says the machine should have free, see {@code local-models/catalog.txt})
 * @param requires ids of add-ons that must be on for this one to work
 * @param hostCommand the exact command (or the exact edit) that adds this add-on to a default
 *     install by hand; empty for a planned add-on. The aggregate command for the whole wanted set is
 *     {@code apply.command} in the API, which is the one to run
 * @param learnMore repository-relative docs path, optionally with an anchor
 * @param unlocks stable keys of what the add-on turns on in the console (see {@link Unlock})
 * @param model Ollama tag, for {@link Kind#MODEL_PULL} only
 * @param compose what the host must do, for {@link Kind#COMPOSE_PROFILE} only
 * @param observe how core can see for itself that the add-on is running, when it can
 */
public record AddonDefinition(
        String id,
        String name,
        String description,
        Category category,
        Kind kind,
        Availability availability,
        int sizeMb,
        int minRamMb,
        boolean needsGpu,
        String license,
        boolean free,
        List<String> requires,
        boolean defaultOn,
        String hostCommand,
        String learnMore,
        List<String> unlocks,
        String model,
        Compose compose,
        Observe observe) {

    /** Catalog grouping; the console lists add-ons by it. */
    public enum Category {
        @JsonProperty("assistant")
        ASSISTANT,
        @JsonProperty("search")
        SEARCH,
        @JsonProperty("documents")
        DOCUMENTS,
        @JsonProperty("models")
        MODELS,
        @JsonProperty("media")
        MEDIA,
        @JsonProperty("tools")
        TOOLS
    }

    /** How the add-on is switched on, and so who does it. */
    public enum Kind {
        /** The host starts containers or changes {@code .env}; core only records the wish. */
        @JsonProperty("compose-profile")
        COMPOSE_PROFILE,
        /** Core pulls the model into the Ollama it already talks to, with progress. */
        @JsonProperty("model-pull")
        MODEL_PULL,
        /** Reserved. Not switchable: there is no executor for it yet, so the catalog may only list it as planned. */
        @JsonProperty("mcp-server")
        MCP_SERVER,
        /** Listed so people can see what is coming; cannot be switched on. */
        @JsonProperty("planned")
        PLANNED
    }

    /** Whether the add-on can be switched on today. */
    public enum Availability {
        @JsonProperty("available")
        AVAILABLE,
        @JsonProperty("planned")
        PLANNED
    }

    /**
     * Stable keys for what an add-on turns on in the console. A page asks "is anything that unlocks
     * {@code web_search} on?", never "is the add-on called search on?".
     */
    public enum Unlock {
        WEB_SEARCH("web_search"),
        DOCUMENTS("documents"),
        TRANSCRIPTION("transcription"),
        ASSISTANT_TERMINAL("assistant_terminal"),
        BROWSER_AUTOMATION("browser_automation"),
        LOCAL_MODELS("local_models"),
        MCP_TOOLS("mcp_tools"),
        IMAGE_GENERATION("image_generation"),
        SPEECH("speech");

        private final String wire;

        Unlock(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        static boolean known(String key) {
            for (Unlock u : values()) {
                if (u.wire.equals(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * What the host has to do for a {@link Kind#COMPOSE_PROFILE} add-on, in the terms of {@code
     * scripts/vegaduta personal}: {@code --profile} names, bare flags, and {@code deploy/compose/.env}
     * keys. All three may be combined; at least one must be present.
     */
    public record Compose(List<String> profiles, List<String> flags, Map<String, String> env) {
        public Compose {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            flags = flags == null ? List.of() : List.copyOf(flags);
            // File order, so the API and the installer see the keys in the order the catalog lists them.
            env = env == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(env));
        }

        boolean isEmpty() {
            return profiles.isEmpty() && flags.isEmpty() && env.isEmpty();
        }
    }

    /** How core itself can tell the add-on is running: a property of its own configuration. */
    public record Observe(String property, String test) {
        /** The property has any non-blank value. */
        public static final String NON_BLANK = "non-blank";
        /** The property equals {@code true}, ignoring case. */
        public static final String TRUE = "true";
    }

    public AddonDefinition {
        requires = requires == null ? List.of() : List.copyOf(requires);
        unlocks = unlocks == null ? List.of() : List.copyOf(unlocks);
    }

    public boolean isAvailable() {
        return availability == Availability.AVAILABLE;
    }
}
