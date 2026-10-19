package io.vegaduta.agent.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Small helpers shared by the stores. */
final class Jdbc {

  private Jdbc() {}

  static void setString(PreparedStatement ps, int index, @Nullable String value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  static void setInstant(PreparedStatement ps, int index, @Nullable Instant value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      ps.setTimestamp(index, Timestamp.from(value));
    }
  }

  static @Nullable Instant getInstant(ResultSet rs, String column) throws SQLException {
    Timestamp ts = rs.getTimestamp(column);
    return ts == null ? null : ts.toInstant();
  }

  static RuntimeException wrap(String what, SQLException e) {
    return new IllegalStateException(what + ": " + e.getMessage(), e);
  }
}
