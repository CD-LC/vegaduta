#!/usr/bin/env bash
# DCO sign-off check for a pull request (the "DCO sign-off" job in ci.yml).
#
#   bash scripts/ci/dco-check.sh <base-sha> <head-sha> [<pr-author-login>]
#
# Every non-merge commit in <base>..<head> must carry a "Signed-off-by:" trailer whose
# email is the commit author's (what `git commit -s` writes). See CONTRIBUTING.md.
#
# One exception: a pull request opened by Dependabot or by the GitHub Actions bot
# (<pr-author-login> is "dependabot[bot]" or "github-actions[bot]") is not a human
# certifying origin, and those bots never sign off, so a commit on such a pull request is
# skipped when its author is that bot's own noreply identity. Both must hold:
#   - the pull request was opened by the bot (the login comes from the GitHub event, which
#     a commit cannot forge), and
#   - the commit's author email is that same bot's noreply address (a Dependabot pull
#     request does not exempt a commit by github-actions[bot], or the reverse).
# A human commit pushed onto a Dependabot branch (for example the regenerated
# requirements.lock, docs/maintainers/dependabot-prs.md) is still checked, and a human
# pull request containing a commit that merely claims a bot's name still fails.
#
# Exit 0 when every checked commit is signed off (or is a skipped bot commit), 1 otherwise.
set -uo pipefail

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo "usage: $0 <base-sha> <head-sha> [<pr-author-login>]" >&2
  exit 2
fi
base=$1
head=$2
pr_author=${3:-}

is_bot_login() { # a pull request opened by one of the two bots that never sign off
  case "$1" in
    'dependabot[bot]' | 'github-actions[bot]') return 0 ;;
    *) return 1 ;;
  esac
}

is_bot_email() { # <email> <bot login>: the bot's own GitHub noreply identity, "<id>+<login>@users.noreply.github.com"
  local e want
  e=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
  want="${2}@users.noreply.github.com"
  [ "$e" = "$want" ] || [[ "$e" == *"+${want}" ]]
}

bad=0
checked=0
while read -r sha; do
  [ -n "$sha" ] || continue
  email="$(git log -1 --format=%ae "$sha")"
  subject="$(git log -1 --format=%s "$sha")"
  if is_bot_login "$pr_author" && is_bot_email "$email" "$pr_author"; then
    echo "skip    ${sha:0:12} ${subject} (bot commit on a ${pr_author} pull request)"
    continue
  fi
  checked=$((checked + 1))
  if git log -1 --format=%B "$sha" | grep -i '^Signed-off-by: ' | grep -qiF "<${email}>"; then
    echo "ok      ${sha:0:12} ${subject}"
  else
    echo "::error::commit ${sha:0:12} has no 'Signed-off-by:' trailer for its author <${email}>"
    bad=1
  fi
done < <(git rev-list --no-merges "${base}..${head}")

if [ "$bad" -ne 0 ]; then
  echo "Sign off every commit: git rebase --signoff ${base:0:12} && git push --force-with-lease"
  exit 1
fi
echo "DCO: ${checked} commit(s) checked and signed off."
