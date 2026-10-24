---
title: Tool parameter: local_shell.background
description: The description of the 'background' parameter in the input schema of the local_shell tool.
risk: TOOL_DESCRIPTION
securityCritical: false
disableable: false
retired: false
strictPlaceholders: false
maxLength: 1000
---
Set true for a dev server or any long-running process (npm run dev, python -m http.server, ./mvnw spring-boot:run). It keeps running after this call and its address is returned as preview_port; the person sees it in the console's Preview pane. Read the port from the PORT environment variable and listen on 0.0.0.0, for example `PORT=$PORT npm run dev -- --host 0.0.0.0`. Do not combine with & or nohup.
