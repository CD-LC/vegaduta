package com.agenticai.core.common.roi;

import com.agenticai.core.billing.resolution.ResolutionEvidence;
import com.agenticai.core.billing.resolution.ResolutionMeteringService;
import com.agenticai.core.billing.resolution.ResolutionOutcome;
import com.agenticai.core.common.roi.RoiDtos.OutcomeRequest;
import com.agenticai.core.common.roi.RoiDtos.OutcomeResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lets an agent, a workflow or an operator mark how a conversation ended. It reuses the existing
 * resolution meter instead of keeping a second ledger:
 *
 * <ul>
 *   <li>{@code RESOLVED} -> {@code RESOLVED_AI}, {@code HANDED_TO_HUMAN} -> {@code ESCALATED},
 *       {@code ABANDONED} -> {@code ABANDONED}, all with evidence {@code EXPLICIT_API} and
 *       confidence 1.0, exactly as {@code POST /api/tenant/resolutions} records them. A RESOLVED
 *       mark is therefore billable on an outcome-priced plan, like any explicit resolution.
 *   <li>{@code CORRECTED_BY_HUMAN} -> a {@code conversation_correction} row (a correction is a
 *       property of the conversation, not an end state; corrections that arrive as {@code
 *       turn_feedback} EDIT rows are read directly and need no mark).
 * </ul>
 *
 * Repeating the same mark is a no-op; a later, different mark supersedes it in the metrics, except
 * that re-asserting an earlier mark after a different one is also a no-op ({@code recorded=false}).
 *
 * <p>Transactional on purpose: a raw {@code JdbcTemplate} only runs in the tenant's schema when it
 * joins the JPA-bound connection that {@code SchemaMultiTenantConnectionProvider} scopes, which
 * needs an open transaction; outside one it would read the default schema. {@code RoiApiIT} fails
 * if that is removed.
 */
@Service
@Transactional
public class RoiOutcomeService {

    private final ResolutionMeteringService metering;
    private final JdbcTemplate jdbc;

    public RoiOutcomeService(ResolutionMeteringService metering, JdbcTemplate jdbc) {
        this.metering = metering;
        this.jdbc = jdbc;
    }

    public OutcomeResponse mark(OutcomeRequest req, String subject) {
        if (req == null
                || req.conversationKey() == null
                || req.conversationKey().isBlank()
                || req.conversationKey().length() > 160
                || req.agentId() == null
                || req.outcome() == null) {
            throw bad("conversationKey (<= 160 chars), agentId and outcome are required");
        }
        Integer agents =
                jdbc.queryForObject(
                        "SELECT count(*) FROM agent WHERE id = ?", Integer.class, req.agentId());
        if (agents == null || agents == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No such agent for this tenant");
        }
        String key = req.conversationKey().trim();
        String outcome = req.outcome().trim().toUpperCase(Locale.ROOT);
        if (outcome.equals("CORRECTED_BY_HUMAN")) {
            int rows =
                    jdbc.update(
                            "INSERT INTO conversation_correction (id, conversation_key, agent_id, marked_by, note)"
                                    + " VALUES (?,?,?,?,?) ON CONFLICT (conversation_key) DO NOTHING",
                            UUID.randomUUID(),
                            key,
                            req.agentId(),
                            subject,
                            truncate(req.note(), 500));
            return new OutcomeResponse(key, outcome, rows > 0);
        }
        ResolutionOutcome mapped =
                switch (outcome) {
                    case "RESOLVED" -> ResolutionOutcome.RESOLVED_AI;
                    case "HANDED_TO_HUMAN" -> ResolutionOutcome.ESCALATED;
                    case "ABANDONED" -> ResolutionOutcome.ABANDONED;
                    default ->
                            throw bad(
                                    "outcome must be one of RESOLVED, HANDED_TO_HUMAN, ABANDONED, CORRECTED_BY_HUMAN");
                };
        boolean recorded =
                metering.record(
                                new ResolutionMeteringService.RecordRequest(
                                        key,
                                        req.agentId(),
                                        req.channel(),
                                        mapped,
                                        ResolutionEvidence.EXPLICIT_API,
                                        BigDecimal.ONE,
                                        Instant.now(),
                                        "roi:" + key + ":" + outcome,
                                        subject))
                        .isPresent();
        return new OutcomeResponse(key, outcome, recorded);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
