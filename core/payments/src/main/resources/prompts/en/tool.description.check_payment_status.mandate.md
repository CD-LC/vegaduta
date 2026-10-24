---
title: Tool description: check_payment_status.mandate
description: The description of the check_payment_status tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Checks a mandate payment transaction created with create_payment_link or request_upi_collect. Returns status (PENDING_APPROVAL / AWAITING_PAYMENT / CAPTURED / FAILED / EXPIRED), paid=true once confirmed, and receiptUrl/shortUrl when available. Only report a payment as received when paid=true - never assume it from the customer saying they paid.
