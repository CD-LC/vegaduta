"""Framework-free tests for app/dataquery.py - the pure planner behind the data
API. The point of most of these is injection: nothing a caller sends may reach
the SQL string except through the introspected-schema allowlist.

Run: python3 -m tests.test_dataquery   (from the app-builder directory)
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app import dataquery as dq  # noqa: E402
from app.dataquery import Column, QueryError  # noqa: E402

PASS = 0
FAIL = 0


def check(name: str, cond: bool, extra: str = ""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  ok    {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name} {extra}")


def raises(fn, code: str | None = None) -> bool:
    try:
        fn()
    except QueryError as e:
        return code is None or e.code == code
    except Exception as e:  # noqa: BLE001
        print("    (unexpected exception type:", type(e).__name__, e, ")")
        return False
    return False


COLS = {
    "id": Column("id", "integer", False, True),
    "title": Column("title", "text"),
    "done": Column("done", "boolean"),
    "meta": Column("meta", "jsonb"),
    "tags": Column("tags", "ARRAY"),
    'weird"name': Column('weird"name', "text"),
}
S = "app_abc_demo"


def main() -> int:
    print("== identifier quoting ==")
    check("simple", dq.q("notes") == '"notes"')
    check("embedded quote is doubled", dq.q('a"b') == '"a""b"')
    check("qualified", dq.qualified("s", "t") == '"s"."t"')

    print("== select ==")
    check("star -> all columns", dq.parse_select("*", COLS) == list(COLS))
    check("empty -> all columns", dq.parse_select(None, COLS) == list(COLS))
    check("explicit list", dq.parse_select("id,title", COLS) == ["id", "title"])
    check("dedupes", dq.parse_select("id,id", COLS) == ["id"])
    check(
        "unknown column rejected",
        raises(lambda: dq.parse_select("id,nope", COLS), "unknown_column"),
    )
    check(
        "injection in select rejected",
        raises(lambda: dq.parse_select("id; DROP TABLE notes--", COLS), "unknown_column"),
    )
    check(
        "subquery in select rejected",
        raises(lambda: dq.parse_select("(select 1)", COLS), "unknown_column"),
    )

    print("== order ==")
    check("default asc", dq.parse_order("title", COLS) == [("title", "ASC")])
    check("desc", dq.parse_order("id.desc", COLS) == [("id", "DESC")])
    check(
        "nulls modifier", dq.parse_order("id.desc.nullslast", COLS) == [("id", "DESC NULLS LAST")]
    )
    check("bad modifier rejected", raises(lambda: dq.parse_order("id.sideways", COLS), "bad_order"))
    check(
        "injection in order rejected",
        raises(lambda: dq.parse_order("id desc; drop table x", COLS), "unknown_column"),
    )
    check(
        "too many terms",
        raises(lambda: dq.parse_order(",".join(["id"] * 6), COLS), "too_many_order_terms"),
    )

    print("== limit/offset ==")
    check("default limit", dq.parse_limit(None) == dq.DEFAULT_LIMIT)
    check("limit capped", dq.parse_limit("999999") == dq.MAX_LIMIT)
    check("limit must be int", raises(lambda: dq.parse_limit("1;drop"), "bad_limit"))
    check("limit must be >= 1", raises(lambda: dq.parse_limit("0"), "bad_limit"))
    check("negative offset rejected", raises(lambda: dq.parse_offset("-1"), "bad_offset"))
    check("offset capped", dq.parse_offset("99999999999") == dq.MAX_OFFSET)

    print("== filters ==")
    f = dq.parse_filters(
        [
            ("id", "eq.5"),
            ("title", "ilike.*milk*"),
            ("done", "is.true"),
            ("id", "in.(1,2,3)"),
            ("title", "not.eq.x"),
        ],
        COLS,
    )
    check("five filters parsed", len(f) == 5)
    check("like wildcard translated", f[1].value == "%milk%")
    check("in list parsed", f[3].value == ["1", "2", "3"])
    check("negate flag", f[4].negate is True and f[4].op == "eq")
    check(
        "reserved params are not filters",
        dq.parse_filters([("limit", "5"), ("select", "id")], COLS) == [],
    )
    check(
        "unknown column rejected",
        raises(lambda: dq.parse_filters([("nope", "eq.1")], COLS), "unknown_column"),
    )
    check(
        "missing operator rejected",
        raises(lambda: dq.parse_filters([("id", "5")], COLS), "bad_filter"),
    )
    check(
        "unknown operator rejected",
        raises(lambda: dq.parse_filters([("id", "regex.1")], COLS), "bad_filter"),
    )
    check(
        "is only null/true/false",
        raises(lambda: dq.parse_filters([("id", "is.1")], COLS), "bad_filter"),
    )
    check("in needs parens", raises(lambda: dq.parse_filters([("id", "in.1,2")], COLS), "bad_in"))
    check(
        "in quoted comma",
        dq.parse_filters([("title", 'in.("a,b",c)')], COLS)[0].value == ["a,b", "c"],
    )
    check(
        "in too large",
        raises(
            lambda: dq.parse_filters([("id", "in.(" + ",".join(["1"] * 101) + ")")], COLS), "bad_in"
        ),
    )
    check(
        "too many filters",
        raises(lambda: dq.parse_filters([("id", "eq.1")] * 21, COLS), "too_many_filters"),
    )

    print("== select SQL ==")
    sql, params = dq.build_select(
        S,
        "notes",
        COLS,
        ["id", "title"],
        dq.parse_filters([("id", "gte.2"), ("title", "like.a*")], COLS),
        [("id", "DESC")],
        10,
        5,
    )
    check(
        "select shape",
        sql
        == 'SELECT "id", "title" FROM "app_abc_demo"."notes" WHERE "id" >= %s AND "title" LIKE %s '
        'ORDER BY "id" DESC LIMIT %s OFFSET %s',
        sql,
    )
    check("params in order", params == ["2", "a%", 10, 5])
    hostile = "1' OR '1'='1"
    sql, params = dq.build_select(
        S, "notes", COLS, ["id"], dq.parse_filters([("title", "eq." + hostile)], COLS), [], 5, 0
    )
    check(
        "hostile value is a bind param, never in SQL", hostile not in sql and hostile in params[0]
    )
    sql, _ = dq.build_select(
        S, "notes", COLS, ["id"], dq.parse_filters([("id", "in.(1,2)")], COLS), [], 5, 0
    )
    check("in expands placeholders", '"id" IN (%s, %s)' in sql)
    sql, _ = dq.build_select(
        S, "notes", COLS, ["id"], dq.parse_filters([("done", "is.null")], COLS), [], 5, 0
    )
    check("is null is a literal keyword", '"done" IS NULL' in sql)
    sql, _ = dq.build_select(
        S, "notes", COLS, ["id"], dq.parse_filters([("id", "not.eq.1")], COLS), [], 5, 0
    )
    check("negation wraps the clause", 'NOT ("id" = %s)' in sql)
    sql, _ = dq.build_select(S, "t", COLS, ['weird"name'], [], [], 1, 0)
    check("odd column name is escaped", '"weird""name"' in sql)

    print("== insert ==")
    sql, params = dq.build_insert(S, "notes", COLS, {"title": "hi", "done": False})
    check(
        "single insert",
        sql == 'INSERT INTO "app_abc_demo"."notes" ("title", "done") VALUES (%s, %s) RETURNING *',
        sql,
    )
    check("insert params", params == ["hi", False])
    sql, params = dq.build_insert(S, "notes", COLS, [{"title": "a"}, {"title": "b", "done": True}])
    check("missing keys fall back to DEFAULT", "(%s, DEFAULT), (%s, %s)" in sql, sql)
    sql, _ = dq.build_insert(S, "notes", COLS, {"title": "a"}, returning=False)
    check("minimal return omits RETURNING", "RETURNING" not in sql)
    check(
        "unknown insert column rejected",
        raises(lambda: dq.build_insert(S, "notes", COLS, {"nope": 1}), "unknown_column"),
    )
    check(
        "column-name injection in body rejected",
        raises(
            lambda: dq.build_insert(S, "notes", COLS, {'title"); drop table notes;--': 1}),
            "unknown_column",
        ),
    )
    check("empty body rejected", raises(lambda: dq.build_insert(S, "notes", COLS, {}), "bad_body"))
    check("empty array rejected", raises(lambda: dq.build_insert(S, "notes", COLS, []), "bad_body"))
    check(
        "non-object row rejected",
        raises(lambda: dq.build_insert(S, "notes", COLS, ["x"]), "bad_body"),
    )
    check(
        "too many rows rejected",
        raises(lambda: dq.build_insert(S, "notes", COLS, [{"title": "x"}] * 101), "bad_body"),
    )
    check(
        "object into text column rejected",
        raises(lambda: dq.build_insert(S, "notes", COLS, {"title": {"a": 1}}), "bad_value"),
    )
    sql, params = dq.build_insert(S, "notes", COLS, {"meta": {"a": 1}, "tags": ["x", "y"]})
    check("object into jsonb is wrapped", type(params[0]).__name__ == "Jsonb")
    check("list into array column passes through", params[1] == ["x", "y"])

    print("== update / delete ==")
    flt = dq.parse_filters([("id", "eq.1")], COLS)
    sql, params = dq.build_update(S, "notes", COLS, {"title": "new"}, flt)
    check(
        "update shape",
        sql == 'UPDATE "app_abc_demo"."notes" SET "title" = %s WHERE "id" = %s RETURNING *',
        sql,
    )
    check("update params order", params == ["new", "1"])
    check(
        "update without filter refused",
        raises(lambda: dq.build_update(S, "notes", COLS, {"title": "x"}, []), "filter_required"),
    )
    check(
        "update unknown column refused",
        raises(lambda: dq.build_update(S, "notes", COLS, {"nope": 1}, flt), "unknown_column"),
    )
    sql, params = dq.build_delete(S, "notes", COLS, flt)
    check(
        "delete shape", sql == 'DELETE FROM "app_abc_demo"."notes" WHERE "id" = %s RETURNING *', sql
    )
    check(
        "delete without filter refused",
        raises(lambda: dq.build_delete(S, "notes", COLS, []), "filter_required"),
    )

    print("== response encoding ==")
    import datetime
    import decimal
    import uuid

    out = dq.dumps(
        [
            {
                "d": decimal.Decimal("1.5"),
                "t": datetime.datetime(2026, 1, 2, 3, 4, 5),
                "u": uuid.UUID(int=1),
                "b": b"\x01\xff",
            }
        ]
    )
    check(
        "types encode",
        '"d":1.5' in out
        and "2026-01-02T03:04:05" in out
        and "00000000-0000-0000-0000-000000000001" in out
        and "\\\\x01ff" in out,
        out,
    )

    print(f"\n{PASS} passed, {FAIL} failed")
    return 1 if FAIL else 0


if __name__ == "__main__":
    raise SystemExit(main())
