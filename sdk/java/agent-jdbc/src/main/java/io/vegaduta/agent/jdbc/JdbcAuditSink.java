package io.vegaduta.agent.jdbc;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.audit.AuditEvent;
import io.vegaduta.agent.api.audit.AuditOutcome;
import io.vegaduta.agent.api.audit.AuditSink;
import io.vegaduta.agent.api.risk.AsiRisk;
import io.vegaduta.agent.core.hook.JsonLite;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Appends audit events to the {@code vegaduta_audit} table and reads them back by run. Rows are
 * never updated or deleted by the SDK: retention is the host's decision (the EU AI Act asks
 * high-risk deployers for at least six months).
 */
public final class JdbcAuditSink implements AuditSink {

  private static final String INSERT =
      "INSERT INTO vegaduta_audit (id, created_at, actor_id, action_type, target_type, target_id, outcome,"
          + " detail, tenant_id, principal, agent_id, session_id, run_id, correlation_id, risks, attributes)"
          + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String SELECT_BY_RUN =
      "SELECT id, created_at, actor_id, action_type, target_type, target_id, outcome, detail, tenant_id,"
          + " principal, agent_id, session_id, run_id, correlation_id, risks, attributes FROM vegaduta_audit"
          + " WHERE run_id = ? ORDER BY created_at, id";

  private final DataSource dataSource;

  public JdbcAuditSink(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public void emit(AuditEvent e) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(INSERT)) {
      RunContext ctx = e.context();
      ps.setString(1, e.id().toString());
      Jdbc.setInstant(ps, 2, e.createdAt());
      ps.setString(3, e.actorId());
      ps.setString(4, e.actionType());
      ps.setString(5, e.targetType());
      ps.setString(6, truncate(e.targetId(), 500));
      ps.setString(7, e.outcome().name());
      Jdbc.setString(ps, 8, e.detail());
      Jdbc.setString(ps, 9, ctx.tenantId());
      Jdbc.setString(ps, 10, ctx.principal());
      Jdbc.setString(ps, 11, ctx.agentId());
      Jdbc.setString(ps, 12, ctx.sessionId());
      ps.setString(13, ctx.runId());
      Jdbc.setString(ps, 14, ctx.correlationId());
      Jdbc.setString(
          ps,
          15,
          e.risks().isEmpty()
              ? null
              : e.risks().stream().map(AsiRisk::id).collect(Collectors.joining(",")));
      ps.setString(16, JsonLite.writeObject(e.attributes()));
      ps.executeUpdate();
    } catch (SQLException ex) {
      throw Jdbc.wrap("Writing audit event " + e.id(), ex);
    }
  }

  public List<AuditEvent> findByRun(String runId) {
    List<AuditEvent> events = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT_BY_RUN)) {
      ps.setString(1, runId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          events.add(read(rs));
        }
      }
      return events;
    } catch (SQLException e) {
      throw Jdbc.wrap("Reading audit events of run " + runId, e);
    }
  }

  private static AuditEvent read(ResultSet rs) throws SQLException {
    RunContext ctx =
        RunContext.builder()
            .tenantId(rs.getString("tenant_id"))
            .principal(rs.getString("principal"))
            .agentId(rs.getString("agent_id"))
            .sessionId(rs.getString("session_id"))
            .runId(rs.getString("run_id"))
            .correlationId(rs.getString("correlation_id"))
            .build();
    String risks = rs.getString("risks");
    Set<AsiRisk> riskSet = EnumSet.noneOf(AsiRisk.class);
    if (risks != null && !risks.isBlank()) {
      for (String id : risks.split(",")) {
        riskSet.add(AsiRisk.valueOf(id.strip()));
      }
    }
    Instant createdAt = Objects.requireNonNull(Jdbc.getInstant(rs, "created_at"));
    return new AuditEvent(
        UUID.fromString(rs.getString("id")),
        createdAt,
        rs.getString("actor_id"),
        rs.getString("action_type"),
        rs.getString("target_type"),
        rs.getString("target_id"),
        AuditOutcome.valueOf(rs.getString("outcome")),
        rs.getString("detail"),
        ctx,
        riskSet,
        JsonLite.readFlatObject(rs.getString("attributes")));
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
