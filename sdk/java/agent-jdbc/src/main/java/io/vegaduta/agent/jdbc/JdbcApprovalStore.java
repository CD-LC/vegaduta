package io.vegaduta.agent.jdbc;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.approval.ApprovalStatus;
import io.vegaduta.agent.api.approval.ApprovalStore;
import io.vegaduta.agent.core.hook.JsonLite;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/** Approval requests in the {@code vegaduta_approval} table. */
public final class JdbcApprovalStore implements ApprovalStore {

  private static final String COLUMNS =
      "id, action_type, summary, payload, tenant_id, principal, agent_id, session_id, run_id,"
          + " correlation_id, attributes, requested_by, status, created_at, expires_at, decided_at,"
          + " decided_by, decision_reason, result";

  private static final String INSERT =
      "INSERT INTO vegaduta_approval ("
          + COLUMNS
          + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

  private static final String UPDATE =
      "UPDATE vegaduta_approval SET status = ?, decided_at = ?, decided_by = ?, decision_reason = ?,"
          + " result = ? WHERE id = ?";

  private static final String SELECT = "SELECT " + COLUMNS + " FROM vegaduta_approval";

  private final DataSource dataSource;

  public JdbcApprovalStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public void save(ApprovalRequest r) {
    try (Connection c = dataSource.getConnection()) {
      try (PreparedStatement ps = c.prepareStatement(UPDATE)) {
        ps.setString(1, r.status().name());
        Jdbc.setInstant(ps, 2, r.decidedAt());
        Jdbc.setString(ps, 3, r.decidedBy());
        Jdbc.setString(ps, 4, r.decisionReason());
        Jdbc.setString(ps, 5, r.resultJson());
        ps.setString(6, r.id().toString());
        if (ps.executeUpdate() > 0) {
          return;
        }
      }
      try (PreparedStatement ps = c.prepareStatement(INSERT)) {
        RunContext ctx = r.context();
        ps.setString(1, r.id().toString());
        ps.setString(2, r.actionType());
        ps.setString(3, r.summary());
        ps.setString(4, r.payloadJson());
        Jdbc.setString(ps, 5, ctx.tenantId());
        Jdbc.setString(ps, 6, ctx.principal());
        Jdbc.setString(ps, 7, ctx.agentId());
        Jdbc.setString(ps, 8, ctx.sessionId());
        ps.setString(9, ctx.runId());
        Jdbc.setString(ps, 10, ctx.correlationId());
        ps.setString(11, JsonLite.writeObject(ctx.attributes()));
        ps.setString(12, r.requestedBy());
        ps.setString(13, r.status().name());
        Jdbc.setInstant(ps, 14, r.createdAt());
        Jdbc.setInstant(ps, 15, r.expiresAt());
        Jdbc.setInstant(ps, 16, r.decidedAt());
        Jdbc.setString(ps, 17, r.decidedBy());
        Jdbc.setString(ps, 18, r.decisionReason());
        Jdbc.setString(ps, 19, r.resultJson());
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw Jdbc.wrap("Saving approval request " + r.id(), e);
    }
  }

  @Override
  public Optional<ApprovalRequest> find(UUID id) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(SELECT + " WHERE id = ?")) {
      ps.setString(1, id.toString());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(read(rs)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw Jdbc.wrap("Finding approval request " + id, e);
    }
  }

  @Override
  public List<ApprovalRequest> findPending(@Nullable String tenantId, int limit) {
    String sql =
        SELECT
            + " WHERE status = ?"
            + (tenantId == null ? "" : " AND tenant_id = ?")
            + " ORDER BY created_at DESC";
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setString(1, ApprovalStatus.PENDING.name());
      if (tenantId != null) {
        ps.setString(2, tenantId);
      }
      ps.setMaxRows(limit);
      return readAll(ps);
    } catch (SQLException e) {
      throw Jdbc.wrap("Listing pending approval requests", e);
    }
  }

  @Override
  public List<ApprovalRequest> findByRun(String runId) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(SELECT + " WHERE run_id = ? ORDER BY created_at")) {
      ps.setString(1, runId);
      return readAll(ps);
    } catch (SQLException e) {
      throw Jdbc.wrap("Listing approval requests of run " + runId, e);
    }
  }

  private static List<ApprovalRequest> readAll(PreparedStatement ps) throws SQLException {
    List<ApprovalRequest> list = new ArrayList<>();
    try (ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        list.add(read(rs));
      }
    }
    return list;
  }

  private static ApprovalRequest read(ResultSet rs) throws SQLException {
    RunContext context =
        RunContext.builder()
            .tenantId(rs.getString("tenant_id"))
            .principal(rs.getString("principal"))
            .agentId(rs.getString("agent_id"))
            .sessionId(rs.getString("session_id"))
            .runId(rs.getString("run_id"))
            .correlationId(rs.getString("correlation_id"))
            .attributes(JsonLite.readFlatObject(rs.getString("attributes")))
            .build();
    Instant createdAt = Objects.requireNonNull(Jdbc.getInstant(rs, "created_at"));
    Instant expiresAt = Objects.requireNonNull(Jdbc.getInstant(rs, "expires_at"));
    return new ApprovalRequest(
        UUID.fromString(rs.getString("id")),
        rs.getString("action_type"),
        rs.getString("summary"),
        rs.getString("payload"),
        context,
        rs.getString("requested_by"),
        ApprovalStatus.valueOf(rs.getString("status")),
        createdAt,
        expiresAt,
        Jdbc.getInstant(rs, "decided_at"),
        rs.getString("decided_by"),
        rs.getString("decision_reason"),
        rs.getString("result"));
  }
}
