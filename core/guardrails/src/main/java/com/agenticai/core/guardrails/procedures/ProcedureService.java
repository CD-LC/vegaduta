package com.agenticai.core.guardrails.procedures;

import com.agenticai.core.guardrails.procedures.dto.ProcedureRequest;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * CRUD + attachment surface for the procedures layer. Reads used by the runtime path ({@link
 * #activeRulesForAgent}) return fully-resolved, deterministically ordered rule views -
 * position-ordered procedures, then rule-order - so both consumers ({@link
 * ProcedurePromptService}'s prompt block and {@link ProcedureGuardEvaluator}'s tool-call checks)
 * iterate the same stable sequence without re-sorting.
 */
@Service
public class ProcedureService {

    private final ProcedureRepository procedureRepository;
    private final ProcedureRuleRepository ruleRepository;
    private final AgentProcedureLinkRepository linkRepository;
    private final ObjectMapper objectMapper;

    public ProcedureService(
            ProcedureRepository procedureRepository,
            ProcedureRuleRepository ruleRepository,
            AgentProcedureLinkRepository linkRepository,
            ObjectMapper objectMapper) {
        this.procedureRepository = procedureRepository;
        this.ruleRepository = ruleRepository;
        this.linkRepository = linkRepository;
        this.objectMapper = objectMapper;
    }

    /** One fully-resolved procedure with its rules, in deterministic order. */
    public record ResolvedProcedure(Procedure procedure, List<ProcedureRule> rules) {}

    @Transactional(readOnly = true)
    public List<Procedure> listAll() {
        return procedureRepository.findAllByOrderByNameAsc();
    }

    @Transactional(readOnly = true)
    public Procedure get(UUID id) {
        return procedureRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new ProcedureNotFoundException(
                                        "Procedure " + id + " does not exist."));
    }

    @Transactional(readOnly = true)
    public ResolvedProcedure getResolved(UUID id) {
        return new ResolvedProcedure(
                get(id), ruleRepository.findByProcedureIdOrderByRuleOrderAsc(id));
    }

    @Transactional
    public Procedure create(ProcedureRequest request, String createdBy) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        Procedure procedure =
                procedureRepository.save(
                        new Procedure(tenantId, request.name(), request.description(), createdBy));
        replaceRules(procedure.getId(), request.rules());
        return procedure;
    }

    @Transactional
    public Procedure update(UUID id, ProcedureRequest request) {
        Procedure procedure = get(id);
        procedure.applyEdit(request.name(), request.description());
        replaceRules(id, request.rules());
        return procedureRepository.save(procedure);
    }

    @Transactional
    public Procedure setStatus(UUID id, ProcedureStatus status) {
        Procedure procedure = get(id);
        procedure.setStatus(status);
        return procedureRepository.save(procedure);
    }

    @Transactional
    public void delete(UUID id) {
        get(id);
        linkRepository.deleteByProcedureId(id);
        ruleRepository.deleteByProcedureId(id);
        procedureRepository.deleteById(id);
    }

    /**
     * Every ACTIVE procedure attached to {@code agentId} with its rules, in attachment-position
     * order. The runtime path (prompt injection, guard evaluation) calls this; DRAFT and ARCHIVED
     * procedures never reach an agent.
     */
    @Transactional(readOnly = true)
    public List<ResolvedProcedure> activeRulesForAgent(UUID agentId) {
        List<AgentProcedureLink> links = linkRepository.findByAgentIdOrderByPositionAsc(agentId);
        if (links.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = links.stream().map(AgentProcedureLink::getProcedureId).toList();
        Map<UUID, Procedure> byId = new LinkedHashMap<>();
        for (Procedure p : procedureRepository.findAllById(ids)) {
            byId.put(p.getId(), p);
        }
        Map<UUID, List<ProcedureRule>> rulesByProcedure = new LinkedHashMap<>();
        for (ProcedureRule r :
                ruleRepository.findByProcedureIdInOrderByProcedureIdAscRuleOrderAsc(ids)) {
            rulesByProcedure.computeIfAbsent(r.getProcedureId(), k -> new ArrayList<>()).add(r);
        }
        List<ResolvedProcedure> resolved = new ArrayList<>();
        for (AgentProcedureLink link : links) {
            Procedure procedure = byId.get(link.getProcedureId());
            if (procedure == null || procedure.getStatus() != ProcedureStatus.ACTIVE) {
                continue;
            }
            resolved.add(
                    new ResolvedProcedure(
                            procedure,
                            rulesByProcedure.getOrDefault(procedure.getId(), List.of())));
        }
        return resolved;
    }

    // --- Agent attachment ---

    @Transactional(readOnly = true)
    public List<ResolvedProcedure> listForAgent(UUID agentId) {
        List<AgentProcedureLink> links = linkRepository.findByAgentIdOrderByPositionAsc(agentId);
        List<ResolvedProcedure> out = new ArrayList<>();
        for (AgentProcedureLink link : links) {
            procedureRepository
                    .findById(link.getProcedureId())
                    .ifPresent(
                            p ->
                                    out.add(
                                            new ResolvedProcedure(
                                                    p,
                                                    ruleRepository
                                                            .findByProcedureIdOrderByRuleOrderAsc(
                                                                    p.getId()))));
        }
        return out;
    }

    /**
     * Attach (or re-position) a procedure on an agent. Idempotent on the (agent, procedure) pair -
     * a repeat attach just updates the position, matching how a "reorder" affordance in a console
     * would call this.
     */
    @Transactional
    public void attach(UUID agentId, UUID procedureId, int position) {
        get(procedureId);
        AgentProcedureLink link =
                linkRepository
                        .findByAgentIdAndProcedureId(agentId, procedureId)
                        .orElseGet(() -> new AgentProcedureLink(agentId, procedureId, position));
        link.setPosition(position);
        linkRepository.save(link);
    }

    @Transactional
    public void detach(UUID agentId, UUID procedureId) {
        if (linkRepository.findByAgentIdAndProcedureId(agentId, procedureId).isEmpty()) {
            throw new ProcedureNotFoundException(
                    "Procedure " + procedureId + " is not attached to agent " + agentId + ".");
        }
        linkRepository.deleteByAgentIdAndProcedureId(agentId, procedureId);
    }

    private void replaceRules(UUID procedureId, List<ProcedureRequest.RuleInput> rules) {
        ruleRepository.deleteByProcedureId(procedureId);
        if (rules == null) {
            return;
        }
        List<ProcedureRule> rows = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            ProcedureRequest.RuleInput input = rules.get(i);
            String guardConfig = null;
            if (input.guardConfig() != null && !input.guardConfig().isEmpty()) {
                try {
                    guardConfig = objectMapper.writeValueAsString(input.guardConfig());
                } catch (Exception e) {
                    throw new ProcedureValidationException(
                            "Rule "
                                    + (i + 1)
                                    + " has a guardConfig that is not serializable JSON: "
                                    + e.getMessage());
                }
            }
            validateGuard(input, i + 1);
            rows.add(
                    new ProcedureRule(
                            procedureId,
                            i,
                            input.instruction(),
                            input.guardKind(),
                            input.guardTool(),
                            guardConfig));
        }
        ruleRepository.saveAll(rows);
    }

    private void validateGuard(ProcedureRequest.RuleInput input, int ruleNumber) {
        if (input.guardKind() == null) {
            return;
        }
        if (input.guardTool() == null || input.guardTool().isBlank()) {
            throw new ProcedureValidationException(
                    "Rule "
                            + ruleNumber
                            + " uses guard "
                            + input.guardKind()
                            + " but names no guardTool.");
        }
        if (input.guardKind() == ProcedureGuardKind.REQUIRE_TOOL_BEFORE) {
            Object prior = input.guardConfig() == null ? null : input.guardConfig().get("prior");
            if (prior == null || prior.toString().isBlank()) {
                throw new ProcedureValidationException(
                        "Rule "
                                + ruleNumber
                                + " uses REQUIRE_TOOL_BEFORE but guardConfig.prior is missing "
                                + "- name the tool that must run first.");
            }
        }
    }
}
