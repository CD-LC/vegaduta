"""Pure query planning for the data API: PostgREST-style URL syntax in,
parameterized SQL out. No database access and no framework imports, so every
rule here is unit-testable and the security-relevant surface is small.

The two invariants that make this safe to put behind a public key:

1. **Identifiers are never taken from user text.** A table or column name only
   reaches the SQL string after it has been looked up in the introspected
   schema (`Schema`), and is then double-quoted. An unknown name is a 400.
2. **Values are never interpolated.** Every value is a bind parameter (`%s`).

Everything else (caps on limit, filters, rows per insert, `in` list size) is
there to keep a single request cheap.
"""

from __future__ import annotations

import json
from dataclasses import dataclass

DEFAULT_LIMIT = 100
MAX_LIMIT = 1000
MAX_OFFSET = 1_000_000
MAX_FILTERS = 20
MAX_IN_VALUES = 100
MAX_INSERT_ROWS = 100
MAX_ORDER_TERMS = 5
MAX_SELECT_COLUMNS = 100
MAX_AFFECTED_ROWS = 1000

RESERVED_PARAMS = frozenset({"select", "order", "limit", "offset"})
_COMPARE_OPS = {"eq": "=", "neq": "<>", "gt": ">", "gte": ">=", "lt": "<", "lte": "<="}
_LIKE_OPS = {"like": "LIKE", "ilike": "ILIKE"}
_IS_VALUES = {"null": "NULL", "true": "TRUE", "false": "FALSE"}


class QueryError(Exception):
    """A request the caller can fix. `code` is stable for clients."""

    def __init__(self, message: str, code: str = "bad_request", status: int = 400):
        super().__init__(message)
        self.message = message
        self.code = code
        self.status = status


@dataclass(frozen=True)
class Column:
    name: str
    data_type: str  # information_schema.columns.data_type, e.g. "integer", "jsonb", "ARRAY"
    nullable: bool = True
    has_default: bool = False


# {table: {column: Column}} - the allowlist every identifier is checked against.
Schema = dict[str, dict[str, Column]]


def q(ident: str) -> str:
    """Quote an identifier. Only ever called with names that came from the
    introspected schema; the doubling is belt-and-braces."""
    return '"' + ident.replace('"', '""') + '"'


def qualified(schema_name: str, table: str) -> str:
    return f"{q(schema_name)}.{q(table)}"


@dataclass(frozen=True)
class Filter:
    column: str
    op: str  # eq neq gt gte lt lte like ilike in is
    value: object  # str | list[str] | "null"/"true"/"false"
    negate: bool = False


# ------------------------------------------------------------------ parsing


def _col(name: str, columns: dict[str, Column]) -> Column:
    c = columns.get(name)
    if c is None:
        raise QueryError(f"unknown column: {name[:80]!r}", "unknown_column")
    return c


def parse_select(raw: str | None, columns: dict[str, Column]) -> list[str]:
    """Selected column names, validated. `None`/empty/"*" means all columns."""
    if raw is None or raw.strip() in ("", "*"):
        return list(columns)
    names = [p.strip() for p in raw.split(",")]
    if len(names) > MAX_SELECT_COLUMNS:
        raise QueryError("too many columns in select", "too_many_columns")
    out: list[str] = []
    for n in names:
        if n == "*":
            return list(columns)
        _col(n, columns)
        if n not in out:
            out.append(n)
    return out


def parse_order(raw: str | None, columns: dict[str, Column]) -> list[tuple[str, str]]:
    """`col`, `col.asc`, `col.desc` (optionally `.nullsfirst`/`.nullslast`),
    comma-separated -> [(column, "ASC DESC [NULLS ...]")]."""
    if not raw or not raw.strip():
        return []
    terms = [t.strip() for t in raw.split(",")]
    if len(terms) > MAX_ORDER_TERMS:
        raise QueryError("too many order terms", "too_many_order_terms")
    out: list[tuple[str, str]] = []
    for t in terms:
        parts = t.split(".")
        name, mods = parts[0], [m.lower() for m in parts[1:]]
        _col(name, columns)
        direction = "ASC"
        nulls = ""
        for m in mods:
            if m == "asc":
                direction = "ASC"
            elif m == "desc":
                direction = "DESC"
            elif m == "nullsfirst":
                nulls = " NULLS FIRST"
            elif m == "nullslast":
                nulls = " NULLS LAST"
            else:
                raise QueryError(f"bad order modifier: {m[:40]!r}", "bad_order")
        out.append((name, direction + nulls))
    return out


def _int_param(raw: str | None, name: str, default: int, lo: int, hi: int) -> int:
    if raw is None or raw == "":
        return default
    try:
        v = int(raw)
    except ValueError:
        raise QueryError(f"{name} must be an integer", f"bad_{name}") from None
    if v < lo:
        raise QueryError(f"{name} must be >= {lo}", f"bad_{name}")
    return min(v, hi)


def parse_limit(raw: str | None) -> int:
    return _int_param(raw, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT)


def parse_offset(raw: str | None) -> int:
    return _int_param(raw, "offset", 0, 0, MAX_OFFSET)


def _split_in_list(raw: str) -> list[str]:
    """`(a,b,"c,d")` -> ["a","b","c,d"]. Double quotes allow commas."""
    s = raw.strip()
    if not (s.startswith("(") and s.endswith(")")):
        raise QueryError("in. expects a parenthesised list: in.(a,b)", "bad_in")
    s = s[1:-1]
    out: list[str] = []
    cur: list[str] = []
    in_quote = False
    for ch in s:
        if ch == '"':
            in_quote = not in_quote
        elif ch == "," and not in_quote:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    if in_quote:
        raise QueryError("unterminated quote in in.(...)", "bad_in")
    out.append("".join(cur))
    out = [v.strip() for v in out] if s.strip() else []
    if not out:
        raise QueryError("in.() needs at least one value", "bad_in")
    if len(out) > MAX_IN_VALUES:
        raise QueryError(f"in. accepts at most {MAX_IN_VALUES} values", "bad_in")
    return out


def parse_filters(items: list[tuple[str, str]], columns: dict[str, Column]) -> list[Filter]:
    """Every query parameter that is not select/order/limit/offset is a filter:
    `col=op.value`, optionally `col=not.op.value`."""
    out: list[Filter] = []
    for key, raw in items:
        if key in RESERVED_PARAMS:
            continue
        _col(key, columns)
        negate = False
        rest = raw
        if rest.startswith("not."):
            negate, rest = True, rest[4:]
        op, sep, val = rest.partition(".")
        if not sep:
            raise QueryError(f"filter on {key!r} must look like op.value (e.g. eq.5)", "bad_filter")
        op = op.lower()
        if op in _COMPARE_OPS:
            out.append(Filter(key, op, val, negate))
        elif op in _LIKE_OPS:
            out.append(Filter(key, op, val.replace("*", "%"), negate))
        elif op == "in":
            out.append(Filter(key, "in", _split_in_list(val), negate))
        elif op == "is":
            v = val.lower()
            if v not in _IS_VALUES:
                raise QueryError("is. accepts null, true or false", "bad_filter")
            out.append(Filter(key, "is", v, negate))
        else:
            raise QueryError(f"unknown operator: {op[:20]!r}", "bad_filter")
        if len(out) > MAX_FILTERS:
            raise QueryError(f"at most {MAX_FILTERS} filters", "too_many_filters")
    return out


# ----------------------------------------------------------------- building


def _where(filters: list[Filter]) -> tuple[str, list]:
    clauses: list[str] = []
    params: list = []
    for f in filters:
        col = q(f.column)
        if f.op in _COMPARE_OPS:
            clause = f"{col} {_COMPARE_OPS[f.op]} %s"
            params.append(f.value)
        elif f.op in _LIKE_OPS:
            clause = f"{col} {_LIKE_OPS[f.op]} %s"
            params.append(f.value)
        elif f.op == "in":
            vals = list(f.value)  # type: ignore[arg-type]
            clause = f"{col} IN ({', '.join(['%s'] * len(vals))})"
            params.extend(vals)
        else:  # is
            clause = f"{col} IS {_IS_VALUES[str(f.value)]}"
        clauses.append(f"NOT ({clause})" if f.negate else clause)
    if not clauses:
        return "", params
    return " WHERE " + " AND ".join(clauses), params


def build_select(
    schema_name: str,
    table: str,
    columns: dict[str, Column],
    select: list[str],
    filters: list[Filter],
    order: list[tuple[str, str]],
    limit: int,
    offset: int,
) -> tuple[str, list]:
    where, params = _where(filters)
    sql = f"SELECT {', '.join(q(c) for c in select)} FROM {qualified(schema_name, table)}{where}"
    if order:
        sql += " ORDER BY " + ", ".join(f"{q(c)} {d}" for c, d in order)
    sql += " LIMIT %s OFFSET %s"
    params = params + [limit, offset]
    return sql, params


def _adapt(column: Column, value: object) -> object:
    """Validate a JSON body value for a column and wrap it if Postgres needs
    help (json/jsonb). Scalars pass through as bind parameters."""
    if value is None or isinstance(value, (bool, int, float, str)):
        if column.data_type in ("json", "jsonb") and value is not None:
            from psycopg.types.json import Jsonb  # lazy: tests of the planner need no driver

            return Jsonb(value)
        return value
    if isinstance(value, (dict, list)):
        if column.data_type in ("json", "jsonb"):
            from psycopg.types.json import Jsonb

            return Jsonb(value)
        if column.data_type == "ARRAY" and isinstance(value, list):
            return value
        raise QueryError(
            f"column {column.name!r} does not accept an object/array value", "bad_value"
        )
    raise QueryError(f"unsupported value for column {column.name!r}", "bad_value")


def _check_row(row: object, columns: dict[str, Column]) -> dict:
    if not isinstance(row, dict) or not row:
        raise QueryError("each row must be a non-empty JSON object", "bad_body")
    for k in row:
        _col(k, columns)
    return row


def build_insert(
    schema_name: str, table: str, columns: dict[str, Column], body: object, returning: bool = True
) -> tuple[str, list]:
    rows = body if isinstance(body, list) else [body]
    if not rows:
        raise QueryError("empty insert", "bad_body")
    if len(rows) > MAX_INSERT_ROWS:
        raise QueryError(f"at most {MAX_INSERT_ROWS} rows per insert", "bad_body")
    rows = [_check_row(r, columns) for r in rows]
    used = [c for c in columns if any(c in r for r in rows)]
    placeholders: list[str] = []
    params: list = []
    for r in rows:
        cells = []
        for c in used:
            if c in r:
                cells.append("%s")
                params.append(_adapt(columns[c], r[c]))
            else:
                cells.append("DEFAULT")
        placeholders.append("(" + ", ".join(cells) + ")")
    sql = (
        f"INSERT INTO {qualified(schema_name, table)} "
        f"({', '.join(q(c) for c in used)}) VALUES {', '.join(placeholders)}"
    )
    if returning:
        sql += " RETURNING *"
    return sql, params


def build_update(
    schema_name: str,
    table: str,
    columns: dict[str, Column],
    body: object,
    filters: list[Filter],
    returning: bool = True,
) -> tuple[str, list]:
    if not filters:
        raise QueryError("update needs at least one filter", "filter_required")
    row = _check_row(body, columns)
    sets = ", ".join(f"{q(c)} = %s" for c in row)
    params: list = [_adapt(columns[c], v) for c, v in row.items()]
    where, wparams = _where(filters)
    sql = f"UPDATE {qualified(schema_name, table)} SET {sets}{where}"
    if returning:
        sql += " RETURNING *"
    return sql, params + wparams


def build_delete(
    schema_name: str,
    table: str,
    columns: dict[str, Column],
    filters: list[Filter],
    returning: bool = True,
) -> tuple[str, list]:
    if not filters:
        raise QueryError("delete needs at least one filter", "filter_required")
    where, params = _where(filters)
    sql = f"DELETE FROM {qualified(schema_name, table)}{where}"
    if returning:
        sql += " RETURNING *"
    return sql, params


# ---------------------------------------------------------------- responses


def json_default(o: object):
    """JSON encoding for the Python types psycopg returns."""
    import datetime
    import decimal
    import uuid

    if isinstance(o, decimal.Decimal):
        return float(o)
    if isinstance(o, (datetime.datetime, datetime.date, datetime.time)):
        return o.isoformat()
    if isinstance(o, datetime.timedelta):
        return str(o)
    if isinstance(o, uuid.UUID):
        return str(o)
    if isinstance(o, (bytes, bytearray, memoryview)):
        return "\\x" + bytes(o).hex()
    if isinstance(o, (set, frozenset)):
        return sorted(o)
    return str(o)


def dumps(rows: object) -> str:
    return json.dumps(rows, default=json_default, separators=(",", ":"))
