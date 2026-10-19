package com.agenticai.core.templates;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.demo.DemoArtifactCopier;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code core/templates}-side implementation of {@code core/tenancy}'s
 * {@link DemoArtifactCopier} seam - what carries a converting demo visitor's
 * agent(s) into their brand-new real tenant. Lives HERE, not in
 * {@code core/agents}, because it is deliberately built out of the community
 * template gallery's two existing halves rather than a hand-rolled entity
 * copy:
 *
 * <ul>
 *   <li>{@link TemplateSnapshotService#snapshotAgent} - THE allow-list
 *       sanitiser. By construction the captured design can never contain
 *       provider credentials, channel secrets, {@code createdBy}, or
 *       tenant-scoped ids (knowledge-collection/collaborator UUIDs are
 *       stripped) - exactly the exclusions the demo-conversion copy needs,
 *       already security-tested for the gallery.</li>
 *   <li>{@link TemplateSnapshotService#toAgentCreateRequest} +
 *       {@link AgentService#create} - the same re-instantiation path a
 *       template import uses, so the target tenant's agent cap, FREE-tier
 *       provider coercion and validation all apply instead of being
 *       silently bypassed by a raw insert (same reasoning as
 *       {@code DemoAgentSeeder}'s "goes through create, not new Agent(...)"
 *       note).</li>
 * </ul>
 *
 * <p><b>TenantContext discipline</b> (the seam's contract): reads run under
 * the demo schema's context, creates under the real schema's, each in its own
 * Hibernate session/transaction ({@code AgentRepository#findAll} and
 * {@code AgentService#create} both open their own), never one enclosing
 * transaction - schema routing is per-session. Whatever context was current
 * before (the signup/onboarding request's own) is restored in a finally.
 *
 * <p><b>The write-phase context carries a {@code null} subject on purpose</b>:
 * {@code AgentService#create} stamps {@code createdBy} from the context
 * subject, and a synthetic system actor there would make the copied agent
 * private to a user that does not exist - invisible to the very visitor it
 * was copied for. {@code createdBy == null} is the established "open to every
 * member of the tenant" value (see {@code ResourceAccessService}), which for
 * a tenant whose only member is the person who just registered is exactly
 * right. At signup time neither call path knows the new owner's Keycloak
 * subject anyway ({@code SignupResponse} does not carry it).
 */
@Component
public class DemoConversionAgentCopier implements DemoArtifactCopier {

    private static final Logger log = LoggerFactory.getLogger(DemoConversionAgentCopier.class);

    /** Read-phase actor label - only ever lands in logs, never on an entity. */
    private static final String DEMO_READ_ACTOR = "system:demo-conversion";

    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final TemplateSnapshotService snapshotService;

    public DemoConversionAgentCopier(
        AgentRepository agentRepository,
        AgentService agentService,
        TemplateSnapshotService snapshotService
    ) {
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.snapshotService = snapshotService;
    }

    @Override
    public Outcome copyAgents(String demoSchema, String realSchema, String demoSessionActorSubject) {
        TenantContext prior = TenantContext.current().orElse(null);
        try {
            // Phase 1 - snapshot only THIS visitor's agent designs out of the
            // (now shared) demo schema, via a DB-level created_by filter -
            // demoSchema holds every live demo visitor's agents at once, so
            // this is load-bearing, not defensive: a bare findAll() would
            // both copy every other visitor's agents into this registration
            // AND load an ever-growing shared table into memory on every
            // conversion. Agent's element collections are all EAGER, and
            // snapshotAgent copies them immediately, so the designs are fully
            // materialised before the context switches away from the demo
            // schema.
            TenantContext.set(TenantContext.of(demoSchema, DEMO_READ_ACTOR, Set.of()));
            List<AgentDesign> designs = agentRepository.findByCreatedBy(demoSessionActorSubject).stream()
                .map(agent -> new AgentDesign(agent.getName(), snapshotService.snapshotAgent(agent)))
                .toList();

            // Phase 2 - re-instantiate each design in the real tenant via the
            // template-import path. Null subject: see the class javadoc.
            TenantContext.set(TenantContext.of(realSchema, null, Set.of()));
            int copied = 0;
            int failed = 0;
            for (AgentDesign design : designs) {
                try {
                    agentService.create(snapshotService.toAgentCreateRequest(design.configJson(), design.name()));
                    copied++;
                } catch (RuntimeException e) {
                    // Per-agent, not whole-copy: one uncopyable agent (agent
                    // cap hit, invalid leftover design) must not cost the rest.
                    failed++;
                    log.warn(
                        "Demo conversion: could not clone agent '{}' from schema {} into {}",
                        design.name(), demoSchema, realSchema, e
                    );
                }
            }
            return new Outcome(copied, failed, designs.size());
        } finally {
            if (prior != null) {
                TenantContext.set(prior);
            } else {
                TenantContext.clear();
            }
        }
    }

    private record AgentDesign(String name, String configJson) {
    }
}
