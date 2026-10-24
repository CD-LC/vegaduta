---
title: Tool description: generate_image
description: The description of the generate_image tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Generates an image from a text description and returns an imageUrl that displays the image INLINE in the chat. This tool cannot return image bytes directly, so to show the image to the user you MUST include the returned imageUrl verbatim in your reply (the chat renders any /api/imagegen/images/... URL inline). Use it when the user asks to create, draw, generate, or illustrate an image, picture, graphic, icon, or artwork from a description.
