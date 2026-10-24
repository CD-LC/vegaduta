---
title: Tool description: request_upi_collect
description: The description of the request_upi_collect tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Requests a UPI payment to a specific payee VPA under your signed payment mandate. The vpa must be on the mandate's allow-list when one is configured. Returns a transactionId - the customer pays via the link/QR; confirm with check_payment_status before treating it as received. May return approval-pending per your mandate's approval policy - do not retry.
