---
title: Tool description: check_payment_status
description: The description of the check_payment_status tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Checks whether a payment link created with create_payment_link has been paid. Returns paid=true with paidAt once the customer's UPI payment is confirmed, otherwise the current status (ready_for_payment = still waiting, expired, canceled). Only report a payment as received when this tool says paid=true - never assume it from the customer saying they paid.
