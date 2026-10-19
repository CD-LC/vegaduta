package com.agenticai.core.agentspi;

/**
 * The platform's own REST contract (OpenAPI 3.1 JSON), so an agent can answer "how do I do X with the
 * API" from the real spec instead of from memory. Implemented by the application assembly, which owns
 * the reflection-based generator; consumed by the Assistant's {@code vegaduta_api_guide} tool.
 */
public interface ApiSpecProvider {

    /** The full OpenAPI document as JSON; built once and cached. Never null. */
    String specJson();
}
