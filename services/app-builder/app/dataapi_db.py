"""Database IO for the data API (see dataapi.py).

Every statement runs on a short-lived connection as the project's own
Postgres role, drawn from a small shared connection budget, inside a
transaction with statement/lock timeouts. Driver errors are mapped to
DataError so no database detail beyond the primary message leaks.
"""

from __future__ import annotations

import threading
import time
from contextlib import contextmanager

from . import dataquery as dq
from .dataapi_errors import DataError
from .settings import Settings

_SCHEMA_TTL = 15.0
_SCHEMA_CACHE: dict[str, tuple[float, dict]] = {}
_DB_SEM: threading.BoundedSemaphore | None = None
_DB_SEM_SIZE = 0
_DB_SEM_LOCK = threading.Lock()


def _semaphore(settings: Settings) -> threading.BoundedSemaphore:
    global _DB_SEM, _DB_SEM_SIZE
    with _DB_SEM_LOCK:
        if _DB_SEM is None or _DB_SEM_SIZE != settings.data_api_max_conns:
            _DB_SEM = threading.BoundedSemaphore(settings.data_api_max_conns)
            _DB_SEM_SIZE = settings.data_api_max_conns
        return _DB_SEM


@contextmanager
def _connection(project, settings: Settings):
    """A short-lived connection as the project's own role, drawn from a small
    shared budget (the database caps total connections for everyone)."""
    sem = _semaphore(settings)
    if not sem.acquire(timeout=3):
        raise DataError(
            503, "busy", "the data service is busy, retry shortly", {"Retry-After": "2"}
        )
    conn = None
    try:
        try:
            import psycopg
            from psycopg.rows import dict_row
        except ImportError as e:  # pragma: no cover - deployment gap
            raise DataError(503, "unavailable", "database driver missing") from e
        try:
            conn = psycopg.connect(
                (project.backend or {})["database_url"], connect_timeout=5, row_factory=dict_row
            )
        except Exception as e:  # noqa: BLE001
            raise DataError(503, "unavailable", "database unreachable") from e
        yield conn
    finally:
        if conn is not None:
            try:
                conn.close()
            except Exception:  # noqa: BLE001
                pass
        sem.release()


def _guard_session(conn, settings: Settings) -> None:
    conn.execute(f"SET LOCAL statement_timeout = {int(settings.data_api_statement_timeout_ms)}")
    conn.execute("SET LOCAL lock_timeout = 2000")
    conn.execute("SET LOCAL idle_in_transaction_session_timeout = 10000")


def _map_db_error(e: Exception) -> DataError:
    try:
        import psycopg.errors as pe
    except ImportError:  # pragma: no cover
        return DataError(500, "database_error", "database error")
    detail = ""
    diag = getattr(e, "diag", None)
    if diag is not None and getattr(diag, "message_primary", None):
        detail = str(diag.message_primary)[:300]
    if isinstance(e, pe.UniqueViolation):
        return DataError(409, "conflict", detail or "unique constraint violated")
    if isinstance(
        e, (pe.NotNullViolation, pe.CheckViolation, pe.ForeignKeyViolation, pe.ExclusionViolation)
    ):
        return DataError(409, "constraint_violation", detail or "constraint violated")
    if isinstance(
        e,
        (
            pe.InvalidTextRepresentation,
            pe.DatatypeMismatch,
            pe.NumericValueOutOfRange,
            pe.StringDataRightTruncation,
            pe.InvalidDatetimeFormat,
            pe.DatetimeFieldOverflow,
            pe.InvalidParameterValue,
            pe.UndefinedColumn,
            pe.UndefinedTable,
            pe.AmbiguousColumn,
            pe.UndefinedFunction,
        ),
    ):
        return DataError(400, "invalid_value", detail or "invalid value")
    if isinstance(e, pe.QueryCanceled):
        return DataError(504, "timeout", "the query took too long")
    if isinstance(e, pe.LockNotAvailable):
        return DataError(409, "locked", "the row is locked, retry")
    if isinstance(e, pe.InsufficientPrivilege):
        return DataError(403, "forbidden", "the database refused this operation")
    return DataError(500, "database_error", "database error")


def introspect(project, settings: Settings, fresh: bool = False) -> dict:
    """{table: {column: Column}} for the project's schema, read as its role."""
    schema_name = project.backend["schema"]
    hit = _SCHEMA_CACHE.get(schema_name)
    if hit and not fresh and time.time() - hit[0] < _SCHEMA_TTL:
        return hit[1]
    with _connection(project, settings) as conn:
        try:
            rows = conn.execute(
                "SELECT c.table_name, c.column_name, c.data_type, c.is_nullable, "
                "c.column_default IS NOT NULL AS has_default "
                "FROM information_schema.columns c "
                "JOIN information_schema.tables t ON t.table_schema = c.table_schema "
                "AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE' "
                "WHERE c.table_schema = %s ORDER BY c.table_name, c.ordinal_position",
                (schema_name,),
            ).fetchall()
        except Exception as e:  # noqa: BLE001
            raise _map_db_error(e) from e
    schema: dict = {}
    for r in rows:
        schema.setdefault(r["table_name"], {})[r["column_name"]] = dq.Column(
            r["column_name"], r["data_type"], r["is_nullable"] == "YES", bool(r["has_default"])
        )
    _SCHEMA_CACHE[schema_name] = (time.time(), schema)
    return schema


def run(project, settings: Settings, sql: str, params: list, *, write: bool):
    """Execute one statement in its own guarded transaction."""
    with _connection(project, settings) as conn:
        try:
            _guard_session(conn, settings)
            cur = conn.execute(sql, params)
            rows = cur.fetchall() if cur.description else []
            affected = cur.rowcount
            if write and affected > dq.MAX_AFFECTED_ROWS:
                # Raising inside the connection context rolls the txn back.
                raise DataError(
                    400,
                    "too_many_rows",
                    f"this change would affect {affected} rows "
                    f"(limit {dq.MAX_AFFECTED_ROWS}); narrow the filter",
                )
            conn.commit()
            return rows, affected
        except DataError:
            raise
        except Exception as e:  # noqa: BLE001
            raise _map_db_error(e) from e
