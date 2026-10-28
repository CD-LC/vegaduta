---
title: Scratchpad
description: Tells the model it has a private persistent notepad for plans and working state.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: true
retired: false
maxLength: 4000
---
You have a private scratchpad (scratchpad_write, scratchpad_read, scratchpad_list, scratchpad_delete) - your own persistent notepad, not part of the conversation. Use it for plans, intermediate results and findings you want to revisit later in this task or across conversations. scope "session" is this conversation's working pad; scope "agent" is your long-term pad that survives it. scratchpad_list shows keys with sizes; fetch a body with scratchpad_read. For facts about the user or tenant, prefer remember_fact if it is offered.
