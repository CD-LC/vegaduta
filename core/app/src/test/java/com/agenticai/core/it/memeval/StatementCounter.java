package com.agenticai.core.it.memeval;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Counts every statement the application executes on its DataSource (JdbcTemplate and JPA alike), for the eval's
 * "database statements per turn" overhead counter. A statement is one {@code execute*} call on a statement object; the
 * two {@code SET search_path} round trips the tenant connection provider issues per checkout are counted too, which is
 * what a real turn pays. Process-wide and cheap: one atomic increment.
 */
public final class StatementCounter {

    private static final AtomicLong COUNT = new AtomicLong();

    private StatementCounter() {}

    /** Statements executed since JVM start (read it before and after a unit of work and subtract). */
    public static long count() {
        return COUNT.get();
    }

    public static DataSource wrap(DataSource ds) {
        return new DelegatingDataSource(ds) {
            @Override
            public Connection getConnection() throws SQLException {
                return counting(super.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return counting(super.getConnection(username, password));
            }
        };
    }

    private static Connection counting(Connection real) {
        return (Connection) Proxy.newProxyInstance(
            StatementCounter.class.getClassLoader(), new Class<?>[] {Connection.class}, new ConnectionHandler(real));
    }

    private record ConnectionHandler(Connection real) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            Object result = call(real, method, args);
            Class<?> type = method.getReturnType();
            if (result != null && Statement.class.isAssignableFrom(type)
                && (method.getName().startsWith("prepare") || method.getName().startsWith("create"))) {
                return Proxy.newProxyInstance(
                    StatementCounter.class.getClassLoader(), new Class<?>[] {type}, new StatementHandler((Statement) result));
            }
            return result;
        }
    }

    private record StatementHandler(Statement real) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            if (method.getName().startsWith("execute")) {
                COUNT.incrementAndGet();
            }
            return call(real, method, args);
        }
    }

    private static Object call(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
