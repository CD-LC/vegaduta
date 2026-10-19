#!/usr/bin/env bash
#
# Create (or update) the labels that the issue templates, .github/labeler.yml,
# .github/dependabot.yml, .github/release.yml and the stale workflow apply.
# Dependabot and the labeler cannot apply a label that does not exist.
#
#   bash scripts/dev/create-labels.sh [OWNER/REPO]     # default: the current gh repo
#
# Needs the GitHub CLI (`gh auth login`) with write access to the repository.

set -euo pipefail
repo_args=()
[ $# -ge 1 ] && repo_args=(--repo "$1")

label() { gh label create "$1" --color "$2" --description "$3" --force "${repo_args[@]}"; }

label bug              d73a4a "Something is not working"
label enhancement      a2eeef "New feature or request"
label needs-triage     fbca04 "Not yet triaged by a maintainer"
label documentation    0075ca "Documentation"
label security         b60205 "Security-related (never put vulnerability details in public issues)"
label breaking-change  b60205 "Changes behavior, configuration or APIs incompatibly"
label proposal         c5def5 "Significant change under discussion (GOVERNANCE.md)"
label dependencies     0366d6 "Dependency update"
label "good first issue" 7057ff "Good for newcomers"
label "help wanted"    008672 "Extra attention is needed"
label pinned           ededed "Exempt from the stale workflow"
label roadmap          ededed "Tracked on the roadmap; exempt from the stale workflow"
label stale            ededed "No recent activity"
label work-in-progress ededed "Not ready for review; exempt from the stale workflow"
label skip-changelog   ededed "Leave out of generated release notes"
for area in ci contracts core deploy examples helm infra migrations sdk services tests web; do
  label "area/$area" bfd4f2 "Touches the $area area"
done
