package io.vegaduta.agent.jdbc;

import io.vegaduta.agent.api.journal.EffectJournal;
import io.vegaduta.agent.core.journal.InterruptedCallSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Effect journal in the {@code vegaduta_effect} table. A row is {@code STARTED} between {@link
 * #begin(String)} and {@link #record(String, String)}, which lets an operator find calls that were
 * interrupted mid-flight after a crash ({@link #findInterrupted(Instant)}). As an {@link
 * InterruptedCallSource} it lets a {@code ReconcilingEffectJournal} reconcile such calls after a
 * restart without any seeding.
 */
public final class JdbcEffectJournal implements EffectJournal, InterruptedCallSource {

  static final String STARTED = "STARTED";
  static final String DONE = "DONE";

  private final DataSource dataSource;
  private final Duration ttl;
  private final Clock clock;

  public JdbcEffectJournal(DataSource dataSource, Duration ttl) {
    this(dataSource, ttl, Clock.systemUTC());
  }

  public JdbcEffectJournal(DataSource dataSource, Duration ttl, Clock clock) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<String> lookup(String key) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT result FROM vegaduta_effect WHERE effect_key = ? AND status = ? AND expires_at > ?")) {
      ps.setString(1, key);
      ps.setString(2, DONE);
      Jdbc.setInstant(ps, 3, clock.instant());
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw Jdbc.wrap("Looking up effect " + key, e);
    }
  }

  @Override
  public void begin(String key) {
    upsert(key, STARTED, null);
  }

  @Override
  public void record(String key, String result) {
    upsert(key, DONE, result);
  }

  private void upsert(String key, String status, String result) {
    Instant now = clock.instant();
    try (Connection c = dataSource.getConnection()) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE vegaduta_effect SET status = ?, result = ?, expires_at = ? WHERE effect_key = ?")) {
        ps.setString(1, status);
        Jdbc.setString(ps, 2, result);
        Jdbc.setInstant(ps, 3, now.plus(ttl));
        ps.setString(4, key);
        if (ps.executeUpdate() > 0) {
          return;
        }
      }
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO vegaduta_effect (effect_key, status, result, created_at, expires_at) VALUES (?,?,?,?,?)")) {
        ps.setString(1, key);
        ps.setString(2, status);
        Jdbc.setString(ps, 3, result);
        Jdbc.setInstant(ps, 4, now);
        Jdbc.setInstant(ps, 5, now.plus(ttl));
        ps.executeUpdate();
      }
    } catch (SQLException e) {
      throw Jdbc.wrap("Writing effect " + key, e);
    }
  }

  @Override
  public Optional<Instant> interruptedSince(String key) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT created_at FROM vegaduta_effect WHERE effect_key = ? AND status = ?")) {
      ps.setString(1, key);
      ps.setString(2, STARTED);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? Optional.ofNullable(Jdbc.getInstant(rs, "created_at"))
            : Optional.empty();
      }
    } catch (SQLException e) {
      throw Jdbc.wrap("Reading interrupted effect " + key, e);
    }
  }

  /** Keys of calls that began before {@code olderThan} and never completed. */
  public java.util.List<String> findInterrupted(Instant olderThan) {
    java.util.List<String> keys = new java.util.ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT effect_key FROM vegaduta_effect WHERE status = ? AND created_at < ?")) {
      ps.setString(1, STARTED);
      Jdbc.setInstant(ps, 2, olderThan);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          keys.add(rs.getString(1));
        }
      }
      return keys;
    } catch (SQLException e) {
      throw Jdbc.wrap("Listing interrupted effects", e);
    }
  }

  /** Deletes expired rows; call from a scheduled job. */
  public int purgeExpired() {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("DELETE FROM vegaduta_effect WHERE expires_at <= ?")) {
      Jdbc.setInstant(ps, 1, clock.instant());
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw Jdbc.wrap("Purging expired effects", e);
    }
  }
}
