---
title: Tool result: image generated, how to show it
description: The instructions in the successful generate_image result: the model must put the returned image path in its reply so the chat shows the picture inline.
risk: STEERS_EVERY_TURN
securityCritical: false
disableable: false
retired: false
maxLength: 4000
placeholders: image_url!
placeholder.image_url: The server-relative URL of the stored image, /api/imagegen/images/<id>
---
The image was generated successfully. To show it to the user you MUST include this exact URL on its own line in your reply: {image_url} — the chat renders it inline as the image. Do NOT claim you cannot generate images.
