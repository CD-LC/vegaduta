#!/usr/bin/env bash
# static-gates: duplicate JPA/Spring NAME-namespace lint (entity names, table
# mappings, default bean names, controller base paths).
#
# WHY (2026-08-17): core/guardrails and core/platform each grew a moderation
# package that independently declared ModerationReviewService,
# ModerationReviewItem and ModerationPolicyPack. Nothing in the toolchain
# objected - both modules compile clean, both pass their unit tests - because
# every one of these collisions lives in a NAME namespace that only exists once
# a single Spring context assembles all core modules together at startup:
#   - Spring's bean registry (default bean name = decapitalised simple class
#     name, so package qualification does NOT disambiguate),
#   - Hibernate's entity-name registry (default entity name = simple class
#     name, same package-blindness),
#   - the request-mapping registry (two controllers claiming one base path).
# The result was a production crashloop a month after the code was written,
# with each layer's collision only visible after fixing the one above it (a
# service, then a Spring Data repository, then a Hibernate entity name). This
# script is the day-one detector: it fails the PR that INTRODUCES the second
# same-named class, not the deploy that boots it.
#
# Relationship to core/app's UniqueBeanNamesTest (run by `mvn verify`, or
# `make spring-wiring`): that test is the exact check for the bean and entity
# namespaces - it reads bytecode and names beans with Spring's own
# AnnotationBeanNameGenerator, so explicit names, nested classes and @Bean
# methods are resolved the way Spring resolves them. It replaced the same-day
# emergency gate scripts/ci/bean-name-collision.sh. This script stays as the
# toolchain-free tier and is the only check for the @Table name+schema and
# @RequestMapping base-path namespaces (which carry an allowlist).
#
# WHAT IT CHECKS (core/*/src/main/java only - test classes don't boot the
# production context; */target/* excluded everywhere because generated/copied
# sources under a stale build dir must never fail a PR the author didn't write):
#   (a) entity: two @Entity classes resolving to the same JPA entity name.
#       Default = simple class name; @Entity(name = "...") overrides, and the
#       override VALUE participates in the namespace (it is not an exemption).
#   (b) table: two @Table annotations declaring the same name+schema pair.
#       Not a boot failure by itself (Hibernate tolerates two entities on one
#       table), but it is the earliest observable symptom of two modules
#       independently inventing the same storage - exactly the moderation
#       failure's shape - so it fails here with an allowlist escape hatch for
#       the rare deliberate shared-table mapping.
#   (c) bean: two @Service/@Component/@Repository/@Controller/@RestController
#       classes with the same decapitalised simple name and NO explicit bean
#       name on either. An explicit name (@Service("...")) is exactly the fix
#       this gate forces, so the parenthesised-with-value form is exempt.
#       Spring Data repository interfaces (interface X extends ...Repository<)
#       are folded into this same namespace - they register beans without any
#       annotation, CANNOT be given an explicit name, and were layer 2 of the
#       moderation crashloop; renaming the interface is their only fix.
#   (d) path: two classes claiming the same class-level @RequestMapping base
#       path. Spring only hard-fails on full method-path ambiguity, but two
#       controllers sharing a base path is the same two-modules-one-name
#       smell one URL segment earlier, and this static tier cannot resolve
#       method-level mappings - so base-path duplicates fail with the same
#       allowlist escape hatch as (b).
#
# Allowlist: scripts/ci/entity-name-lint-allowlist.txt - one "<kind> <key>"
# per line, and every entry MUST carry a trailing "# why this is safe" comment.
# An entry without a reason is itself a FAILURE: an unexplained allowlist line
# is indistinguishable from a silenced bug, which defeats the entire gate.
#
# Whole-tree scan, not changed-files-only (unlike flyway-lint.sh's edit guard,
# which needs a merge-base): a name collision is BETWEEN a new file and an
# arbitrarily old one, so there is no meaningful changed-file scoping - the
# second half of every collision is by definition unchanged. Same cheap tier
# as the other static gates: git + grep + gawk + coreutils, no JDK, seconds.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

ALLOWLIST_FILE="scripts/ci/entity-name-lint-allowlist.txt"

FAIL=0

# Single gawk pass over every production Java file, emitting one record per
# name-namespace membership: "<kind>\t<key>\t<file>:<line>". Same-line-only
# annotation parsing throughout - this codebase writes @Entity/@Table/
# stereotype annotations on one line (verified across core/ when this gate
# was written), and a line-based parser is what keeps this tier JDK-free.
# -prune on target/, not a -not -path post-filter: pruning stops find from
# DESCENDING into build output at all, which on a fully-built local tree is
# the difference between ~5s and ~40s under Windows/git-bash (same
# per-fork/per-stat cost note as flyway-lint.sh's header; ubuntu-latest
# barely notices either way).
RECORDS="$(
  find core -name target -prune -o -path '*/src/main/java/*' -name '*.java' -print0 |
  xargs -0 gawk '
    # New file: clear pending state so nothing leaks across file boundaries.
    FNR == 1 { pend_bean = 0; pend_ent = 0; ent_name = ""; pend_path = ""; seen_type = 0 }

    # Skip comment lines - javadoc prose mentions annotations (e.g. the
    # "@Table(schema = ...)" cross-references in core/platform) and must not
    # be parsed as real declarations.
    /^[[:space:]]*(\*|\/\/|\/\*)/ { next }

    # --- (c) stereotypes -> Spring bean namespace --------------------------
    # Bare form only: a parenthesised form carries an explicit bean name and
    # is the sanctioned fix, so it clears rather than sets the pending flag.
    /^[[:space:]]*@(Service|Component|Repository|Controller|RestController)[[:space:]]*$/ { pend_bean = 1; next }
    /^[[:space:]]*@(Service|Component|Repository|Controller|RestController)\(/            { pend_bean = 0; next }

    # --- (a) @Entity -> Hibernate entity-name namespace --------------------
    # Bare @Entity defaults to the simple class name; an explicit name= value
    # is captured and used as the key (NOT treated as an exemption - two
    # explicit "X"es collide just as hard as two defaults).
    /^[[:space:]]*@Entity[[:space:]]*$/ { pend_ent = 1; ent_name = ""; next }
    /^[[:space:]]*@Entity\(/ {
      pend_ent = 1; ent_name = ""
      if (match($0, /name[[:space:]]*=[[:space:]]*"([^"]*)"/, em)) ent_name = em[1]
      next
    }

    # --- (b) @Table -> physical table namespace ----------------------------
    # Key is "<schema>.<name>"; a @Table without schema= means the JDBC
    # default schema, keyed as "(default)" so it cannot silently equal a
    # named schema. @Table without name= inherits the entity name and is
    # already covered by (a), so only explicit name= is recorded here.
    /^[[:space:]]*@Table\(/ {
      tname = ""; tschema = "(default)"
      if (match($0, /name[[:space:]]*=[[:space:]]*"([^"]*)"/, tm))   tname   = tm[1]
      if (match($0, /schema[[:space:]]*=[[:space:]]*"([^"]*)"/, sm)) tschema = sm[1]
      if (tname != "") print "table\t" tschema "." tname "\t" FILENAME ":" FNR
      next
    }

    # --- (d) class-level @RequestMapping -> base-path namespace ------------
    # Only lines BEFORE the first type declaration count: a class-level
    # mapping always precedes the class keyword, and gating on seen_type is
    # what keeps method-level @RequestMapping (which follows it) out of the
    # class base-path namespace. First quoted string covers the value=/path=/
    # positional forms alike; trailing slash normalised so "/api/x/" and
    # "/api/x" collide as the equivalent mappings they are.
    seen_type == 0 && /^[[:space:]]*@RequestMapping/ {
      if (match($0, /"([^"]*)"/, pm)) {
        p = pm[1]
        sub(/\/+$/, "", p)
        pend_path = (p == "" ? "/" : p)
      }
      next
    }

    # First type declaration claims all pending annotations (intervening
    # annotations like @Transactional fall through without clearing them,
    # matching how the source actually stacks annotations above a class).
    match($0, /(class|interface|enum|record)[[:space:]]+([A-Za-z0-9_]+)/, m) {
      if (pend_bean) {
        # Spring default bean name = java.beans.Introspector.decapitalize of
        # the simple name: first char lowercased UNLESS the first two chars
        # are both uppercase (URLService stays URLService).
        n = m[2]
        if (!(length(n) >= 2 && substr(n, 1, 2) ~ /^[A-Z][A-Z]$/))
          n = tolower(substr(n, 1, 1)) substr(n, 2)
        print "bean\t" n "\t" FILENAME ":" FNR
      }
      if (pend_ent)          print "entity\t" (ent_name == "" ? m[2] : ent_name) "\t" FILENAME ":" FNR
      if (pend_path != "")   print "path\t" pend_path "\t" FILENAME ":" FNR
      pend_bean = 0; pend_ent = 0; ent_name = ""; pend_path = ""
      seen_type = 1
    }

    # --- (c) continued: Spring Data repository interfaces ------------------
    # Structural match (extends ...Repository<) because these register beans
    # with NO annotation at all and can sit several to a file (e.g.
    # ModerationRepositories.java). Their bean name is the decapitalised
    # interface name and CANNOT be overridden by annotation - renaming the
    # interface is the only fix, which is why they share the bean namespace
    # with the stereotypes above rather than getting their own kind.
    match($0, /interface[[:space:]]+([A-Za-z0-9_]+)[[:space:]]+extends[[:space:]][^{]*Repository[[:space:]]*</, r) {
      n = r[1]
      if (!(length(n) >= 2 && substr(n, 1, 2) ~ /^[A-Z][A-Z]$/))
        n = tolower(substr(n, 1, 1)) substr(n, 2)
      print "bean\t" n "\t" FILENAME ":" FNR
    }
  ' | sort -u
)"

TOTAL="$(printf '%s\n' "$RECORDS" | grep -c . || true)"
echo "entity-name-lint: scanned core/*/src/main/java - $TOTAL name-namespace records"

# Load the allowlist. Format: "<kind> <key>" with a MANDATORY "# reason"
# trailing comment - an entry that doesn't explain itself fails the gate
# outright, because an unexplained suppression is exactly the "silently
# waved through" state this gate exists to prevent.
declare -A ALLOWED
if [[ -f "$ALLOWLIST_FILE" ]]; then
  lineno=0
  while IFS= read -r line; do
    lineno=$((lineno + 1))
    # Pure parameter-expansion trimming - no sed/echo subprocesses per line
    # (same Windows/git-bash per-fork-cost reasoning as the find -prune above).
    stripped="${line%%#*}"
    stripped="${stripped#"${stripped%%[![:space:]]*}"}"
    stripped="${stripped%"${stripped##*[![:space:]]}"}"
    [[ -z "$stripped" ]] && continue
    if [[ "$line" != *"#"* ]]; then
      echo "FAIL: $ALLOWLIST_FILE:$lineno - allowlist entry '$stripped' has no '# reason' comment. Every entry must say WHY the duplicate is safe; an unexplained entry defeats the gate." >&2
      FAIL=1
      continue
    fi
    kind="${stripped%%[[:space:]]*}"
    key="${stripped#"$kind"}"
    key="${key#"${key%%[![:space:]]*}"}"
    ALLOWED["$kind"$'\t'"$key"]=1
  done < "$ALLOWLIST_FILE"
fi

# A collision = the same (kind, key) pair recorded at more than one location.
# sort -u above already collapsed exact duplicate records, so uniq -d here
# only fires when the locations genuinely differ.
DUPES="$(printf '%s\n' "$RECORDS" | cut -f1,2 | sort | uniq -d || true)"

if [[ -n "$DUPES" ]]; then
  while IFS=$'\t' read -r kind key; do
    [[ -z "$kind" ]] && continue
    if [[ -n "${ALLOWED["$kind"$'\t'"$key"]:-}" ]]; then
      echo "entity-name-lint: [$kind] $key duplicated but ALLOWLISTED (see $ALLOWLIST_FILE) - not failing."
      continue
    fi
    echo "FAIL: duplicate [$kind] name '$key' - these compile and unit-test clean but collide in a single startup namespace (see this script's header for the 2026-08-17 moderation incident this re-creates):" >&2
    printf '%s\n' "$RECORDS" | gawk -F'\t' -v k="$kind" -v n="$key" '$1 == k && $2 == n { print "    " $3 }' >&2
    FAIL=1
  done <<< "$DUPES"
fi

if [[ "$FAIL" -ne 0 ]]; then
  echo >&2
  echo "entity-name-lint: FAILED. Fixes by kind:" >&2
  echo "  entity - give one side an explicit @Entity(name = \"...\") that is unique across ALL core modules" >&2
  echo "  table  - two modules are mapping the same physical table; either one is wrong, or add an allowlist entry WITH a reason" >&2
  echo "  bean   - give one side @Service(\"...\")/@Component(\"...\")/etc., or rename a Spring Data repository interface (no name attribute exists for those)" >&2
  echo "  path   - move one controller to a distinct base path, or allowlist WITH a reason if the split is deliberate" >&2
  exit 1
fi
echo "entity-name-lint: OK"
