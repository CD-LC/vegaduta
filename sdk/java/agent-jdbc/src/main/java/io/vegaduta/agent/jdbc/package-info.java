/**
 * Durable stores on plain JDBC. Each class takes a {@link javax.sql.DataSource} and uses one
 * connection per operation with auto-commit, so they work inside or outside a host transaction
 * manager.
 */
@NullMarked
package io.vegaduta.agent.jdbc;

import org.jspecify.annotations.NullMarked;
