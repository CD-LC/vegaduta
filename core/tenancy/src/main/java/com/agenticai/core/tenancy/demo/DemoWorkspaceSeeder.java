package com.agenticai.core.tenancy.demo;

/**
 * Dependency-inversion seam so {@code core/tenancy} can put a ready-to-talk
 * agent inside a freshly provisioned demo schema without depending on
 * {@code core/agents}.
 *
 * <p>The module graph runs {@code agents -> tenancy}, never the other way
 * (see the two poms) - the exact same situation {@code TenantStatusGuard}
 * already solves by declaring the interface in {@code core/security} and the
 * implementation in {@code core/tenancy}. This is that pattern again, one
 * layer up: interface here, {@code DemoAgentSeeder} implementation over in
 * {@code core/agents}.
 *
 * <p>Injected as an {@link java.util.Optional} everywhere it is consumed.
 * Absent means "no agents module on the classpath" (a narrow test context) -
 * the sandbox is then provisioned agent-less rather than failing.
 */
public interface DemoWorkspaceSeeder {

    /**
     * Creates the one pre-seeded agent inside the demo tenant's schema.
     *
     * <p><b>Contract:</b> the caller has ALREADY set {@code TenantContext} to
     * {@code tenantSchema} - implementations must not try to switch schemas
     * themselves, and must not clear the context on the way out.
     *
     * @param businessName the visitor's optional, already-sanitised business
     *     name, or {@code null} for the generic sandbox agent.
     * @param persona optional one-click persona preset ({@link DemoPersona});
     *     implementations splice {@link DemoPersona#promptBlock()} into their
     *     prompt skeleton. {@code null} means the generic sandbox agent.
     *     Passed as the enum rather than pre-rendered prompt text so the
     *     shared skeleton (language rules, sandbox honesty rules) stays owned
     *     by the implementation and applies to every persona identically.
     * @return the created agent's id and display name.
     */
    SeededAgent seedDemoAgent(String tenantSchema, String businessName, DemoPersona persona);

    /**
     * Deletes one session's seeded agent, best-effort. Only meaningful now
     * that the demo schema is SHARED across every visitor (see {@code
     * DemoSessionService}'s class javadoc): without this, every reset or
     * expiry would leave the agent row behind forever, and the shared
     * schema's agent count - which every plan's {@code maxAgents} cap
     * counts schema-wide, not per-actor - would only ever grow until new
     * sandboxes can no longer seed an agent at all.
     *
     * <p><b>Contract:</b> same as {@link #seedDemoAgent} - the caller has
     * already bound {@code TenantContext} to {@code tenantSchema} (with the
     * owning session's own subject, so an access check inside the
     * implementation passes), and must not clear it on the way out.
     * Implementations must swallow "already gone" as success, not throw -
     * the caller treats this as pure best-effort cleanup.
     *
     * @param agentId the id {@link #seedDemoAgent} returned; a no-op if null.
     */
    default void deleteDemoAgent(String tenantSchema, java.util.UUID agentId) {
        // Default no-op keeps this addition source-compatible with any other
        // DemoWorkspaceSeeder implementation (e.g. test doubles) that predates it.
    }

    /**
     * /try Agent Studio: remove EVERY agent this sandbox's actor subject
     * created in the demo schema (seeded, studio-built and Magic Studio
     * agents alike). Before this hook only {@code demo_session.agent_id} was
     * cleaned up on reset/expiry, so extra agents leaked into the shared
     * schema for good. Default no-op for source compatibility with test
     * doubles.
     */
    default void deleteAgentsCreatedBy(String tenantSchema, String createdBySubject) {
    }

    record SeededAgent(java.util.UUID agentId, String agentName) {
    }
}
