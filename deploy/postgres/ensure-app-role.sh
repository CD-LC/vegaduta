#!/bin/sh
# =============================================================================
# ensure-app-role.sh - give core a least-privileged PostgreSQL login.
#
# Run by the one-shot `db-roles` compose service (full and lite stacks) on
# every `up`, as the image's bootstrap superuser (POSTGRES_USER). Idempotent:
# on an already-converted database it changes nothing.
#
#   1. creates/updates the login role APP_DB_USER (NOSUPERUSER, NOCREATEDB,
#      NOCREATEROLE, NOREPLICATION, NOBYPASSRLS) with APP_DB_PASSWORD;
#   2. makes it the owner of the application database - that is what lets
#      core create one schema per tenant and run its Flyway migrations
#      (database owner = CREATE on the database, and the `public` schema is
#      owned by pg_database_owner);
#   3. installs the two extensions a non-superuser cannot create itself
#      (pgvector and Apache AGE are not "trusted" extensions) - core's own
#      `CREATE EXTENSION IF NOT EXISTS ...` then becomes a no-op;
#   4. grants the AGE catalog to the role (graph memory);
#   5. hands every application object that an older install created as the
#      superuser (schemas, tables, sequences, views, functions, types) to the
#      app role, so Flyway can keep ALTERing them. Extension members are left
#      alone.
#
# Opt out (keep the pre-1.0 behaviour of core connecting as the superuser):
# set POSTGRES_APP_USER to the same value as POSTGRES_USER. This script then
# exits without changing anything.
#
# Secrets never appear in argv: psql reads the password from the environment
# (\getenv) and the connection password from PGPASSWORD.
# =============================================================================
set -eu

: "${PGUSER:?PGUSER (the superuser) is required}"
: "${PGDATABASE:?PGDATABASE is required}"
: "${APP_DB_USER:?APP_DB_USER is required}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is required (run scripts/vegaduta secrets)}"

if [ "$APP_DB_USER" = "$PGUSER" ]; then
  echo "db-roles: POSTGRES_APP_USER equals the superuser ($PGUSER); core keeps superuser access (not recommended)."
  exit 0
fi

# Wait for the server (depends_on already waits for pg_isready; this covers
# restarts racing the healthcheck).
i=0
until pg_isready -q; do
  i=$((i + 1))
  [ "$i" -ge 60 ] && { echo "db-roles: PostgreSQL not ready after 60s" >&2; exit 1; }
  sleep 1
done

psql -X -v ON_ERROR_STOP=1 -v app_user="$APP_DB_USER" <<'SQL'
\set QUIET on
\getenv app_password APP_DB_PASSWORD

-- 1. the login role
SELECT format('CREATE ROLE %I LOGIN', :'app_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L',
              :'app_user', :'app_password') \gexec
\unset app_password

-- 2. database ownership (CREATE SCHEMA for tenant schemas, CONNECT, TEMP)
SELECT format('ALTER DATABASE %I OWNER TO %I', current_database(), :'app_user') \gexec

-- 3. extensions a non-superuser cannot create
CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;
CREATE EXTENSION IF NOT EXISTS age;

-- 4. AGE catalog. create_graph()/cypher() read and write ag_graph/ag_label.
SELECT format('GRANT USAGE ON SCHEMA ag_catalog TO %I', :'app_user') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ag_catalog TO %I', :'app_user') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ag_catalog TO %I', :'app_user') \gexec

-- 5. adopt objects an earlier install created as the superuser
SET vegaduta.app_user TO :'app_user';
DO $adopt$
DECLARE
  app  text := current_setting('vegaduta.app_user');
  r    record;
  n    int  := 0;
BEGIN
  -- schemas (not system, not extension-owned, not public: that one belongs to pg_database_owner)
  FOR r IN
    SELECT n2.nspname
      FROM pg_namespace n2
     WHERE n2.nspname NOT IN ('public', 'information_schema', 'ag_catalog')
       AND n2.nspname NOT LIKE 'pg\_%'
       AND pg_get_userbyid(n2.nspowner) <> app
       AND NOT EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.classid = 'pg_namespace'::regclass AND d.objid = n2.oid AND d.deptype = 'e')
  LOOP
    EXECUTE format('ALTER SCHEMA %I OWNER TO %I', r.nspname, app); n := n + 1;
  END LOOP;

  -- tables, partitioned tables, foreign tables, views, materialized views, and
  -- sequences not owned by a column (owned sequences follow their table)
  FOR r IN
    SELECT c.oid, c.relkind, format('%I.%I', ns.nspname, c.relname) AS fqn
      FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
     WHERE c.relkind IN ('r', 'p', 'f', 'v', 'm', 'S')
       AND ns.nspname NOT IN ('information_schema', 'ag_catalog')
       AND ns.nspname NOT LIKE 'pg\_%'
       AND pg_get_userbyid(c.relowner) <> app
       AND NOT EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
       AND NOT (c.relkind = 'S' AND EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                          AND d.refclassid = 'pg_class'::regclass AND d.deptype IN ('a', 'i')))
     ORDER BY CASE c.relkind WHEN 'p' THEN 0 WHEN 'r' THEN 1 ELSE 2 END
  LOOP
    -- a partition's owner may already have changed with its parent
    CONTINUE WHEN pg_get_userbyid((SELECT relowner FROM pg_class WHERE oid = r.oid)) = app;
    EXECUTE format('ALTER %s %s OWNER TO %I',
                   CASE r.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                                  WHEN 'S' THEN 'SEQUENCE' WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
                   r.fqn, app);
    n := n + 1;
  END LOOP;

  -- functions and procedures
  FOR r IN
    SELECT p.oid::regprocedure AS sig
      FROM pg_proc p JOIN pg_namespace ns ON ns.oid = p.pronamespace
     WHERE ns.nspname NOT IN ('information_schema', 'ag_catalog')
       AND ns.nspname NOT LIKE 'pg\_%'
       AND pg_get_userbyid(p.proowner) <> app
       AND NOT EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
  LOOP
    EXECUTE format('ALTER ROUTINE %s OWNER TO %I', r.sig, app); n := n + 1;
  END LOOP;

  -- enums, domains, composite and range types (not table row types, not arrays)
  FOR r IN
    SELECT format('%I.%I', ns.nspname, t.typname) AS fqn, t.typtype
      FROM pg_type t JOIN pg_namespace ns ON ns.oid = t.typnamespace
     WHERE t.typtype IN ('e', 'd', 'c', 'r')
       AND (t.typrelid = 0 OR (SELECT relkind FROM pg_class WHERE oid = t.typrelid) = 'c')
       AND ns.nspname NOT IN ('information_schema', 'ag_catalog')
       AND ns.nspname NOT LIKE 'pg\_%'
       AND pg_get_userbyid(t.typowner) <> app
       AND NOT EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e')
  LOOP
    EXECUTE format('ALTER %s %s OWNER TO %I',
                   CASE r.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END, r.fqn, app);
    n := n + 1;
  END LOOP;

  IF n > 0 THEN
    RAISE NOTICE 'db-roles: moved % object(s) to %', n, app;
  END IF;
END
$adopt$;
SQL

echo "db-roles: '$APP_DB_USER' ready (owner of $PGDATABASE, no superuser)."
