---
title: Tool description: google_sheets
description: The description of the google_sheets tool, as sent to the model in the tool list.
risk: TOOL_DESCRIPTION
securityCritical: true
disableable: false
retired: false
strictPlaceholders: false
maxLength: 4000
---
Reads and writes cell ranges in a Google Sheet the connected account can access. Actions: 'read_range' (requires spreadsheet_id, range in A1 notation e.g. 'Sheet1!A1:C10'), 'write_range' (requires spreadsheet_id, range, and rows - a list of rows, each row a list of cell values as strings; overwrites the cells in range), 'append_rows' (requires spreadsheet_id, range naming the table e.g. 'Leads' or 'Leads!A:F', and rows; adds the rows after the table's last row and never overwrites; values are stored as plain text, formulas are not evaluated).
