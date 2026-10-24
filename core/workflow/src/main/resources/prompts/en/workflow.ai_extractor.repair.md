---
title: Workflow parameter extractor, repair request
description: Appended to the extractor's request for its one repair retry when the first reply could not be used. The placeholder is the list of problems found, joined by semicolons. Starts with two line breaks that separate it from the original request.
risk: SECONDARY_CALL
securityCritical: false
disableable: false
retired: false
maxLength: 2000
placeholders: issues!
placeholder.issues: The problems found in the first reply, joined by "; "
---


Your previous reply could not be used: {issues}.
Reply again with ONLY the corrected JSON object - no markdown fences, no commentary. Use null for any value the text does not state.
