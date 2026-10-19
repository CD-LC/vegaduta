-- Direct feedback: "print the correct number without masking and print
-- some information like whats the message" - the RECENT INBOUND panel
-- (ChannelStatusController#recentInbound) had no message content to show
-- at all; ChannelSession never stored anything beyond lastMessageAt (a
-- timestamp, not the text). This column holds a short, truncated preview
-- of the most recent inbound message body/subject for that session, set on
-- every touch() (ChannelSessionService#resolveOrCreate) alongside
-- lastMessageAt. Nullable because existing rows predate this column and a
-- session's first row is written before any message body is known.
ALTER TABLE channel_session ADD COLUMN last_message_preview VARCHAR(500);
