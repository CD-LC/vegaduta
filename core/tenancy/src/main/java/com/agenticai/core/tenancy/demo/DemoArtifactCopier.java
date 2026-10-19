package com.agenticai.core.tenancy.demo;

/**
 * Dependency-inversion seam so {@link DemoConversionService} (in {@code
 * core/tenancy}) can clone a converting visitor's demo agents into their
 * freshly provisioned real tenant without depending on {@code core/agents}/
 * {@code core/templates} - the exact same seam shape as {@link
 * DemoWorkspaceSeeder} (interface here, implementation in a downstream
 * module), and for the exact same module-graph reason: everything else
 * depends on tenancy, never the other way around.
 *
 * <p>The implementation ({@code DemoConversionAgentCopier} in {@code
 * core/templates}) reuses the community-template install flow: each demo
 * agent's design is captured with {@code TemplateSnapshotService#snapshotAgent}
 * (the allow-list snapshot that by construction can never carry credentials,
 * channel secrets, tenant-scoped ids or {@code createdBy}) and re-instantiated
 * via {@code AgentService#create} (so agent caps, FREE-tier provider coercion
 * and validation all apply in the target tenant).
 *
 * <p><b>Contract - unlike {@link DemoWorkspaceSeeder}, the implementation
 * owns {@code TenantContext} switching itself</b>: it must read under the
 * demo schema's context, write under the real schema's context, and restore
 * whatever context was current before it ran. It must NOT run both phases in
 * one enclosing transaction - schema routing is resolved per Hibernate
 * session, so each phase needs its own.
 *
 * <p>Injected as an {@link java.util.Optional} - absent means "no templates
 * module on the classpath" (a narrow test context), in which case the
 * conversion proceeds without a copy rather than failing.
 */
public interface DemoArtifactCopier {

    /**
     * Clones this ONE visitor's agent(s) out of {@code demoSchema} into
     * {@code realSchema}. {@code demoSchema} is now the SHARED demo
     * sandbox's schema (see {@code DemoSessionService}'s class javadoc), so
     * an implementation MUST scope its read to rows whose {@code created_by}
     * equals {@code demoSessionActorSubject} - never "every agent in the
     * schema", which after the shared-tenant change would be every demo
     * visitor's agents at once. {@code demoSessionActorSubject} is always
     * {@code "demo:" + sessionId}, the same subject {@code
     * DemoSessionService#inDemoTenantContext} stamps as {@code created_by}
     * on everything the session ever created.
     *
     * <p>Per-agent failures (e.g. the target plan's agent cap) are logged,
     * counted in {@code failedAgents} and do not abort the rest.
     *
     * @return counts of what was actually cloned; never null.
     * @throws RuntimeException only for whole-copy failures (e.g. the demo
     *     schema is already purged) - the caller treats that as copied=false.
     */
    Outcome copyAgents(String demoSchema, String realSchema, String demoSessionActorSubject);

    record Outcome(int copiedAgents, int failedAgents, int totalAgents) {
    }
}
