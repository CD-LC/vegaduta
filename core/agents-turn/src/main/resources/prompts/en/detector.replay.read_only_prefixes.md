---
title: Replay-safe tool-name prefixes
description: Tool-name prefixes (one per line) that mark an unclassified tool as read-only, so a turn that only called such tools may be re-run on a stronger model or another provider. The prefix must be a whole leading word (browse matches browse_page, never browser_click). Anything not matching is treated as mutating and blocks the re-run. Adding a prefix makes more tools replayable, so changing this needs acknowledgement.
risk: SECONDARY_CALL
securityCritical: true
disableable: false
retired: false
maxLength: 4000
---
get
list
search
read
fetch
find
lookup
look_up
query
retrieve
describe
check
view
recall
load_skill
web_search
browse
calculate
count

