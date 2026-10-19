-- Output node "file" destination (#152): lets a run's file-channel output be
-- downloaded later via GET /api/workflows/runs/{runId}/download. Both
-- columns are nullable and only populated when a run actually has an
-- Output node configured with channel = "file" - every other run leaves
-- them null, same as error_message today.
ALTER TABLE workflow_run ADD COLUMN output_file_name VARCHAR(255);
ALTER TABLE workflow_run ADD COLUMN output_data TEXT;
