---
title: Reply notice: stopped by the provider's safety system
description: The person reads this when the model provider's safety system stopped the reply. Deprecated seed: app.chat.finish.refusal-notice.
risk: USER_VISIBLE
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: reason
placeholder.reason: The provider's own finish reason, e.g. SAFETY, content_filter, refusal
---
The model provider's safety system stopped this reply ({reason}), so there is no answer to show for this request. If you think it was stopped by mistake, change the request or tell your administrator.
