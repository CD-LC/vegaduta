package io.vegaduta.agent.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the SDK tables if they do not exist. Intended for development and tests; production
 * deployments copy {@code io/vegaduta/agent/jdbc/schema-<dialect>.sql} into their migration tool.
 */
public final class VegadutaAgentSchema {

  private static final Logger log = LoggerFactory.getLogger(VegadutaAgentSchema.class);

  private VegadutaAgentSchema() {}

  public enum Dialect {
    POSTGRESQL("schema-postgresql.sql"),
    H2("schema-h2.sql");

    private final String resource;

    Dialect(String resource) {
      this.resource = resource;
    }

    public String resource() {
      return "io/vegaduta/agent/jdbc/" + resource;
    }
  }

  public static Dialect detect(DataSource dataSource) {
    try (Connection c = dataSource.getConnection()) {
      String product = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
      if (product.contains("postgres")) {
        return Dialect.POSTGRESQL;
      }
      if (product.contains("h2")) {
        return Dialect.H2;
      }
      throw new IllegalStateException(
          "Unsupported database '" + product + "'; run the DDL for your database yourself");
    } catch (SQLException e) {
      throw new IllegalStateException("Cannot read database metadata", e);
    }
  }

  public static void initialize(DataSource dataSource) {
    initialize(dataSource, detect(dataSource));
  }

  public static void initialize(DataSource dataSource, Dialect dialect) {
    String ddl = read(dialect.resource());
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement()) {
      for (String statement : ddl.split(";")) {
        String sql = stripComments(statement).strip();
        if (!sql.isEmpty()) {
          st.execute(sql);
        }
      }
      log.info("VegaDuta Agent SDK schema ready ({})", dialect);
    } catch (SQLException e) {
      throw new IllegalStateException("Failed to initialise the VegaDuta Agent SDK schema", e);
    }
  }

  static String read(String resource) {
    try (InputStream in =
        VegadutaAgentSchema.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read " + resource, e);
    }
  }

  private static String stripComments(String sql) {
    StringBuilder sb = new StringBuilder();
    for (String line : sql.split("\n")) {
      String trimmed = line.strip();
      if (!trimmed.startsWith("--")) {
        sb.append(line).append('\n');
      }
    }
    return sb.toString();
  }
}
