---
name: sql-query
description: Write, explain, debug and optimise SQL - SELECT queries, joins, aggregations, window functions, CTEs, schema design, indexes and slow-query tuning for PostgreSQL, MySQL, SQL Server, SQLite, BigQuery and Snowflake. Use when the user asks for a query, shares a schema or a slow or failing query, or asks a data question that should be answered with SQL.
---

# SQL

Correct first, readable second, fast third; and say which dialect you wrote for.

## Before writing

You need the tables, their columns and keys, and the dialect. If the schema was not given, state the schema you are assuming at the top of your answer so the user can correct it. Never guess column names silently.

## Writing queries

- Name every column; no `SELECT *` in anything that will be kept.
- Qualify columns with table aliases as soon as there is a join.
- Use CTEs to give each step a name when a query has more than two logical stages.
- Be explicit about join type and think about what a missing match should do. An inner join that silently drops rows is the most common wrong answer.
- Watch fan-out: joining one-to-many before aggregating double counts. Aggregate first, then join.
- `NULL` is not equal to anything; use `IS NULL`, and remember `NOT IN` with a NULL in the list returns nothing.
- Dates: half-open ranges (`>= start AND < next_start`), and say which time zone.
- Always parameterise user input. Never build SQL by concatenating strings.

## Debugging and tuning

1. Reproduce and read the actual error or the actual plan (`EXPLAIN ANALYZE` or the equivalent for the dialect).
2. Look for sequential scans on large tables, functions wrapped around indexed columns, implicit type casts, and `OR` across different columns.
3. Propose the index with its exact column order and explain why that order.
4. State the trade-off: every index slows writes.

## Output format

The query in a fenced `sql` block, then two or three lines explaining what it does, then any assumptions and edge cases (ties, NULLs, empty result).

## Safety

For `UPDATE`, `DELETE`, `ALTER` and `DROP`: show the matching `SELECT` first so the user can see what will be affected, wrap in a transaction where the dialect allows, and never omit the `WHERE`. Do not run a destructive statement through a tool without the user confirming it.
