---
title: Mutating tool-name words
description: Whole words (one per line) that mark an unclassified tool as mutating even when its name starts with a read verb (get_and_delete). A tool containing any of them is never replayed. The platform always applies the shipped list in addition to an override, so an override can add words but never remove one.
risk: SECONDARY_CALL
securityCritical: true
disableable: false
retired: false
maxLength: 4000
---
delete
remove
send
create
update
write
post
put
patch
drop
click
type
submit
cancel
pay
book
execute
exec
run
set
add
insert
upload
publish
reply
forward
approve
transfer
charge
purchase
buy
sell
merge
commit
push
kill
stop
start
reset
modify
edit
save
store
invoke
call
trigger
email
message
sms
notify

