#!/usr/bin/env bash
#
# Rewrite the project's public identity everywhere in the repository:
# GitHub owner/repo, container-registry namespace, CODEOWNERS team, project
# lead handle, security and conduct contact addresses.
#
# The current values live in scripts/dev/repo-identity.env (placeholders until
# the maintainers settle them). This script finds every occurrence of those
# values in tracked and untracked (non-ignored) text files, replaces them, and
# then updates repo-identity.env, so it can be run again later.
#
# Usage:
#   bash scripts/dev/set-repo-identity.sh --dry-run                # list every occurrence
#   bash scripts/dev/set-repo-identity.sh --dry-run --repo acme/vegaduta
#   bash scripts/dev/set-repo-identity.sh --repo acme/vegaduta \
#        --lead jdoe --security-email security@acme.org --conduct-email conduct@acme.org
#
# Options (each is optional; an omitted value is left unchanged):
#   --repo OWNER/NAME        GitHub repository
#   --registry NAMESPACE     image/chart namespace, default ghcr.io/<owner, lowercased>
#                            when --repo is given
#   --team @ORG/TEAM|@USER   CODEOWNERS owner, default @<owner>/maintainers when
#                            --repo is given (use @<user> for a user-owned repo:
#                            user accounts cannot have teams)
#   --lead HANDLE            project lead's GitHub handle (without @)
#   --security-email ADDR    fallback vulnerability-report address
#   --conduct-email ADDR     Code of Conduct report address
#   --dry-run                print what would change; modify nothing
#
# Only exact, token-bounded matches are replaced (e.g. "vegaduta/vegaduta" but
# not "charts/vegaduta/values.yaml", and "ghcr.io/vegaduta/" including its
# URL-encoded form in badge URLs). Review `git diff` afterwards.

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

ENV_FILE="scripts/dev/repo-identity.env"
SELF="scripts/dev/set-repo-identity.sh"
[ -f "$ENV_FILE" ] || { echo "missing $ENV_FILE" >&2; exit 1; }
command -v perl >/dev/null || { echo "perl is required" >&2; exit 1; }

get() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n1; }
FROM_REPO="$(get REPO_SLUG)"
FROM_REGISTRY="$(get REGISTRY_NAMESPACE)"
FROM_TEAM="$(get MAINTAINERS_TEAM)"
FROM_LEAD="$(get LEAD_HANDLE)"
FROM_SEC="$(get SECURITY_EMAIL)"
FROM_CONDUCT="$(get CONDUCT_EMAIL)"

DRY=0
TO_REPO="" TO_REGISTRY="" TO_TEAM="" TO_LEAD="" TO_SEC="" TO_CONDUCT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY=1 ;;
    --repo) shift; TO_REPO="${1:?--repo needs OWNER/NAME}" ;;
    --registry) shift; TO_REGISTRY="${1:?--registry needs a value}" ;;
    --team) shift; TO_TEAM="${1:?--team needs a value}" ;;
    --lead) shift; TO_LEAD="${1:?--lead needs a value}"; TO_LEAD="${TO_LEAD#@}" ;;
    --security-email) shift; TO_SEC="${1:?--security-email needs a value}" ;;
    --conduct-email) shift; TO_CONDUCT="${1:?--conduct-email needs a value}" ;;
    -h|--help) sed -n '2,36p' "$0"; exit 0 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

if [ -n "$TO_REPO" ]; then
  [[ "$TO_REPO" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$ ]] || { echo "--repo must be OWNER/NAME" >&2; exit 2; }
  owner="${TO_REPO%%/*}"
  [ -n "$TO_REGISTRY" ] || TO_REGISTRY="ghcr.io/$(printf '%s' "$owner" | tr '[:upper:]' '[:lower:]')"
  [ -n "$TO_TEAM" ] || TO_TEAM="@${owner}/maintainers"
fi
if [ -n "$TO_REGISTRY" ] && [ "$TO_REGISTRY" != "$(printf '%s' "$TO_REGISTRY" | tr '[:upper:]' '[:lower:]')" ]; then
  echo "--registry must be lowercase (OCI references are)" >&2; exit 2
fi
for e in "$TO_SEC" "$TO_CONDUCT"; do
  [ -z "$e" ] || [[ "$e" =~ ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$ ]] || { echo "not an email address: $e" >&2; exit 2; }
done

# In dry-run without targets, list occurrences of the current values.
listing_only=0
if [ -z "$TO_REPO$TO_REGISTRY$TO_TEAM$TO_LEAD$TO_SEC$TO_CONDUCT" ]; then
  [ "$DRY" = 1 ] || { echo "nothing to change: pass --repo/--registry/--team/--lead/--security-email/--conduct-email, or --dry-run to list occurrences" >&2; exit 2; }
  listing_only=1
fi

# Text files git knows about (tracked + untracked, not ignored), minus binaries.
mapfile -d '' FILES < <(git ls-files -z -co --exclude-standard -- . ":!:$ENV_FILE" ":!:$SELF" \
  | xargs -0 -r grep -Il --null -e "$FROM_REPO" -e "$FROM_REGISTRY" -e "${FROM_REGISTRY//\//%2F}" \
      -e "$FROM_TEAM" -e "$FROM_LEAD" -e "$FROM_SEC" -e "$FROM_CONDUCT" \
      -e "vegaduta/agentic-ai-platform" 2>/dev/null || true)

export FROM_REPO FROM_REGISTRY FROM_TEAM FROM_LEAD FROM_SEC FROM_CONDUCT
export TO_REPO TO_REGISTRY TO_TEAM TO_LEAD TO_SEC TO_CONDUCT DRY LISTING="$listing_only"

# One perl pass per file. Each rule: [label, regex, replacement-or-empty].
# shellcheck disable=SC2016
PERL='
use strict; use warnings;
my @rules;
sub rule { my ($label, $re, $to) = @_; push @rules, [$label, qr/$re/, $to]; }
my %E = %ENV;
my $enc = sub { my $s = shift; $s =~ s{/}{%2F}g; $s };
my $q = sub { quotemeta(shift) };
rule("repo",     "(?<![A-Za-z0-9_.])" . $q->($E{FROM_REPO}) . "(?![A-Za-z0-9_-])", $E{TO_REPO});
rule("repo",     "(?<=github\.com[:/])vegaduta/agentic-ai-platform(?![A-Za-z0-9_-])", $E{TO_REPO} || $E{FROM_REPO});
rule("registry", "(?:(?<![A-Za-z0-9_.-])|(?<=:-))" . $q->($E{FROM_REGISTRY}) . "(?![A-Za-z0-9_.-])", $E{TO_REGISTRY});
rule("registry", $q->($enc->($E{FROM_REGISTRY})) . "(?=%2F)", $E{TO_REGISTRY} ? $enc->($E{TO_REGISTRY}) : "");
rule("team",     $q->($E{FROM_TEAM}) . "(?![A-Za-z0-9_-])", $E{TO_TEAM});
rule("lead",     "(?<![A-Za-z0-9_-])" . $q->($E{FROM_LEAD}) . "(?![A-Za-z0-9_-])", $E{TO_LEAD});
rule("security-email", $q->($E{FROM_SEC}), $E{TO_SEC});
rule("conduct-email",  $q->($E{FROM_CONDUCT}), $E{TO_CONDUCT});
my %count; my $changed = 0;
for my $file (@ARGV) {
  open(my $in, "<:raw", $file) or die "$file: $!";
  my @lines = <$in>; close $in;
  my $dirty = 0;
  for my $i (0 .. $#lines) {
    for my $r (@rules) {
      my ($label, $re, $to) = @$r;
      next unless $lines[$i] =~ $re;
      my $n = () = $lines[$i] =~ /$re/g;
      if ($E{LISTING} || ($to ne "")) {
        $count{$label} += $n;
        (my $show = $lines[$i]) =~ s/\s+$//; $show = substr($show, 0, 160);
        printf "%-15s %s:%d: %s\n", $label, $file, $i + 1, $show if $E{DRY};
      }
      if (!$E{DRY} && $to ne "") { $lines[$i] =~ s/$re/$to/g; $dirty = 1; }
    }
  }
  if ($dirty) { open(my $out, ">:raw", $file) or die "$file: $!"; print $out @lines; close $out; $changed++; }
}
print "\n";
printf "%-15s %d occurrence(s)\n", $_, $count{$_} for sort keys %count;
printf "%s\n", $E{DRY} ? "dry run: nothing changed" : "$changed file(s) rewritten";
'

echo "current identity ($ENV_FILE):"
printf '  %-15s %s\n' repo "$FROM_REPO" registry "$FROM_REGISTRY" team "$FROM_TEAM" \
  lead "$FROM_LEAD" security-email "$FROM_SEC" conduct-email "$FROM_CONDUCT"
if [ "$listing_only" = 0 ]; then
  echo "new values:"
  printf '  %-15s %s\n' repo "${TO_REPO:-(unchanged)}" registry "${TO_REGISTRY:-(unchanged)}" \
    team "${TO_TEAM:-(unchanged)}" lead "${TO_LEAD:-(unchanged)}" \
    security-email "${TO_SEC:-(unchanged)}" conduct-email "${TO_CONDUCT:-(unchanged)}"
fi
echo

if [ "${#FILES[@]}" -eq 0 ]; then echo "no occurrences found"; exit 0; fi
perl -e "$PERL" -- "${FILES[@]}"

if [ "$DRY" = 0 ]; then
  # Values go through the environment, never into the perl source (an "@org"
  # or "user@host" would otherwise be interpolated as a perl array).
  set_env() {
    [ -n "$2" ] || return 0
    K="$1" V="$2" perl -pi -e 's{^\Q$ENV{K}\E=.*}{$ENV{K}=$ENV{V}}' "$ENV_FILE"
  }
  set_env REPO_SLUG "$TO_REPO"
  set_env REGISTRY_NAMESPACE "$TO_REGISTRY"
  set_env MAINTAINERS_TEAM "$TO_TEAM"
  set_env LEAD_HANDLE "$TO_LEAD"
  set_env SECURITY_EMAIL "$TO_SEC"
  set_env CONDUCT_EMAIL "$TO_CONDUCT"
  echo "updated $ENV_FILE; review with: git diff"
  echo "remaining TODO markers for the identity: grep -rn 'TODO(maintainers)' --include='*.md' ."
fi
