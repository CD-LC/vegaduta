---
title: Tool description: create_payment_link.mandate
description: The description of the create_payment_link tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Creates a UPI payment link (India, INR) under your signed payment mandate - the customer opens it in any UPI app to pay. Your mandate caps (per-transaction, daily, monthly) and approval policy apply; a call may come back as approval-pending, in which case the link is created only after a human approves - do not retry it. Payment is confirmed only when check_payment_status reports paid=true. Identical repeat calls return the same transaction.
