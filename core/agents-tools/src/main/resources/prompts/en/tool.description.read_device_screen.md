---
title: Tool description: read_device_screen
description: The description of the read_device_screen tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads the most recently reported screen content of this tenant's paired phone - the foreground app and a text dump of what's currently on screen (not an image). Use this to answer questions like 'what's on my phone screen' or 'what app am I in right now'. The data is only as fresh as the last time the phone reported it - check the returned timestamp.
