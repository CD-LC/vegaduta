---
title: Tool description: data_table_write
description: The description of the data_table_write tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Write to a workspace data table (stored records that persist between runs). action insert_row adds a row; insert_rows adds several; update_rows changes the rows matching a filter; upsert_row updates the row whose key column matches or adds it; delete_rows removes the rows matching a filter. update_rows and delete_rows need a non-empty filter.
