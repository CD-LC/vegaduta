#!/usr/bin/env bash
# Self-test for dco-check.sh: builds throwaway repositories and expects the check to pass
# or fail on each kind of commit. No network, nothing in this checkout is touched.
# Usage: bash scripts/ci/test-dco-check.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
check="$here/dco-check.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
rc=0

HUMAN_NAME='Punith Nagaraju'
HUMAN_EMAIL='punith@example.com'
DEPENDABOT='dependabot[bot] <49699333+dependabot[bot]@users.noreply.github.com>'
ACTIONS='github-actions[bot] <41898282+github-actions[bot]@users.noreply.github.com>'

new_repo() { # leaves $repo as a repository with one base commit; echoes nothing
  repo="$tmp/r$((++n))"
  mkdir "$repo"
  git -C "$repo" init -q -b main
  git -C "$repo" config user.name "$HUMAN_NAME"
  git -C "$repo" config user.email "$HUMAN_EMAIL"
  git -C "$repo" config commit.gpgsign false
  echo base > "$repo/f"
  git -C "$repo" add f
  git -C "$repo" commit -q -m 'chore: base'
  base=$(git -C "$repo" rev-parse HEAD)
}
n=0

commit() { # message [--author "Name <email>"] [-s]; edits $file (default f)
  local msg=$1; shift
  echo "$RANDOM" >> "$repo/${file:-f}"
  git -C "$repo" add "${file:-f}"
  git -C "$repo" commit -q -m "$msg" "$@"
}

expect() { # name, wanted exit code, pr author ("" for none)
  local name=$1 want=$2 author=$3 out got head
  head=$(git -C "$repo" rev-parse HEAD)
  out=$(cd "$repo" && bash "$check" "$base" "$head" "$author" 2>&1)
  got=$?
  if [ "$got" -ne "$want" ]; then
    echo "FAIL $name: exit $got, wanted $want"
    printf '     %s\n' "${out//$'\n'/$'\n'     }"
    rc=1
  else
    echo "ok   $name"
  fi
}

# Humans must sign off.
new_repo; commit 'feat: signed' -s
expect "human: signed commit passes" 0 "octocat"

new_repo; commit 'feat: unsigned'
expect "human: unsigned commit fails" 1 "octocat"

new_repo; commit 'feat: signed' -s; commit 'fix: forgot'
expect "human: one unsigned commit among signed fails" 1 "octocat"

new_repo; commit 'feat: wrong email' -m 'Signed-off-by: Someone Else <else@example.com>'
expect "human: sign-off for a different email fails" 1 "octocat"

new_repo; commit 'feat: signed' -s
git -C "$repo" checkout -q -b side "$base"; file=g commit 'feat: side' -s
git -C "$repo" checkout -q main; git -C "$repo" merge -q --no-ff --no-edit side -m 'Merge branch side'
expect "human: unsigned merge commit is not checked" 0 "octocat"

# Bots on their own pull requests do not sign off.
new_repo; commit 'build(deps): bump aiohttp' --author "$DEPENDABOT"
expect "dependabot: its own unsigned commit passes" 0 'dependabot[bot]'

new_repo; commit 'ci(deps): bump the actions group' --author "$ACTIONS"
expect "github-actions: its own unsigned commit passes" 0 'github-actions[bot]'

# The exemption needs both the bot's pull request and the bot's identity.
new_repo; commit 'build(deps): bump aiohttp' --author "$DEPENDABOT"
expect "human pull request: a commit claiming to be dependabot fails" 1 "octocat"
expect "no pull request author given: a bot-looking commit fails" 1 ""

new_repo; commit 'build(deps): bump aiohttp' --author 'dependabot[bot] <attacker@example.com>'
expect "dependabot pull request: bot name with a foreign email fails" 1 'dependabot[bot]'

new_repo; commit 'build(deps): bump aiohttp' --author "$DEPENDABOT"; commit 'build(deps): regenerate requirements.lock'
expect "dependabot pull request: an unsigned human commit on top fails" 1 'dependabot[bot]'

new_repo; commit 'build(deps): bump aiohttp' --author "$DEPENDABOT"; commit 'build(deps): regenerate requirements.lock' -s
expect "dependabot pull request: a signed human commit on top passes" 0 'dependabot[bot]'

new_repo; commit 'ci: other' --author "$ACTIONS"
expect "dependabot pull request: another bot's commit is not exempt" 1 'dependabot[bot]'

exit $rc
