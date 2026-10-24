---
title: Invented link: file link pattern
description: Detector. One regular expression (Java syntax, applied to the lower-cased answer) for a link that ends in a generated-file extension. An invalid expression is ignored and the shipped one is used.
risk: STEERS_EVERY_TURN
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
https?://[^\s<>()\[\]"']+\.(pdf|docx?|xlsx?|pptx?|csv|zip|png|jpe?g|gif|webp|svg|mp3|wav|mp4|webm|mov)(?=$|[\s<>()\[\]"'.,;:!?])
