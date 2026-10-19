package com.agenticai.core.mcp.registry;

/**
 * One credential/configuration input a registry server declares it needs -
 * the mapped, frontend-facing form of the registry's raw {@code
 * environmentVariables[]} entry (and of any secret positional CLI argument,
 * see {@link RegistrySearchResult#args()}). The "Add" form renders one field
 * per required spec, masking the value when {@link #secret} is true, then
 * sends the collected values back as the registration's {@code env} map (or
 * splices them into {@code args} for a {@code positional} spec).
 *
 * @param name          the env var / placeholder key (e.g. {@code GITHUB_TOKEN})
 * @param description   human hint from the registry, may be null/blank
 * @param required      whether the server cannot start without it
 * @param secret        whether the UI should mask input and never echo it back
 * @param defaultValue  registry-supplied default, or null - pre-fill only
 * @param positional    true when this value is spliced into the launch args as
 *                      a positional CLI argument (a {@code {{name}}} placeholder
 *                      in {@link RegistrySearchResult#args()}) rather than sent
 *                      as an environment variable - some servers take a
 *                      connection string as an argument, not an env var
 */
public record RegistryEnvVarSpec(
    String name,
    String description,
    boolean required,
    boolean secret,
    String defaultValue,
    boolean positional
) {
}
