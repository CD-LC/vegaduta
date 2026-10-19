-- Who had each recorded agent turn, so feedback on a turn (and the reward and
-- training samples it produces) can only come from that person. NULL for
-- episodes recorded before this column existed: those take no reward from
-- feedback and are left out of preference exports.
ALTER TABLE rl_episode ADD COLUMN subject VARCHAR(255);
