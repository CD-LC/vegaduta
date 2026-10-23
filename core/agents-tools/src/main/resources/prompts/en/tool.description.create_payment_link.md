---
title: Tool description: create_payment_link
description: The description of the create_payment_link tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Creates a UPI payment link (India, INR only) the customer can open in any UPI app to pay the business you work for - use it when the user has agreed to buy something or pay an amount and you need to collect the money. Returns a shortUrl to share with the customer and a checkoutSessionId to check later. Creating a link does NOT mean it has been paid: payment is confirmed separately - call check_payment_status with the checkoutSessionId before telling anyone a payment went through. This action moves money and normally requires a human's approval first; if the result says approval is pending, tell the user the link will be shared once approved and do not retry. Only create one link per purchase.
