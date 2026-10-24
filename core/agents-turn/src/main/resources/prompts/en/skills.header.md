---
title: Skills block header
description: Agents with skills, full wording: tells the model to check the skill list first and call load_skill before answering. The heading line is part of the text.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
---
## Skills
Before you answer, check whether the request matches one of these skills. If it does, your FIRST action must be to call load_skill(name), then follow its instructions - do not answer from memory first. Read bundled files with read_skill_file only if needed. Skip skills that do not match and do not mention skills to the user unless asked.
