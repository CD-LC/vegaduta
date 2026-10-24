package com.agenticai.core.agentspi;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tools a media vendor contributes to agents: language tools (translate, transcribe, transliterate,
 * identify language), image generation or video generation. Kernel-owned (ADR-0011 rule a): {@code
 * tools.AgentMediaToolResolver} asks the sources of each {@link Kind}, applies the kernel's own
 * per-agent gates (e.g. the agent's language-tools opt-in) and wraps every tool in the tool guard;
 * it never imports a vendor. Implementations: {@code sarvam.agenttools.SarvamLanguageToolSource},
 * {@code imagegen.agenttools.ImageGenerationToolSource}, {@code
 * imagegen.agenttools.VideoGenerationToolSource}, {@code
 * elevenlabs.agenttools.MusicGenerationToolSource}.
 */
public interface MediaToolSource {

    /** Which slot of the media tool set this source fills. */
    enum Kind {
        /**
         * Indic-language translate / transcribe / transliterate / identify-language tools
         * (per-agent opt-in).
         */
        LANGUAGE,
        /** {@code generate_image}. */
        IMAGE,
        /** {@code generate_video}. */
        VIDEO,
        /** {@code generate_music}. */
        MUSIC
    }

    Kind kind();

    /**
     * Unguarded tool callbacks for the current tenant, or empty when the vendor is not configured
     * for it. Cheap, non-throwing checks only - the throwing credential resolvers run at call time
     * inside the tools.
     */
    List<ToolCallback> rawTools();

    /**
     * As {@link #rawTools()}, for the agent the tools are being resolved for ({@code agentId} may be null). A source
     * whose tools read an agent-scope prompt override overrides this to pass the agent on; the default ignores it.
     */
    default List<ToolCallback> rawTools(java.util.UUID agentId) {
        return rawTools();
    }
}
