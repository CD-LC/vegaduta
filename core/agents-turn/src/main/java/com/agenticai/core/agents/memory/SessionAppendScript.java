package com.agenticai.core.agents.memory;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The Lua scripts {@link SessionMemoryService} runs against Redis, and the single-round-trip
 * append.
 *
 * <p>Before {@link #APPEND_SCRIPT}, appending one conversation turn took 3 to 6 sequential round
 * trips (RPUSH, [fold claim], LTRIM or the marker-aware trim script, EXPIRE, SETNX of the owner
 * record, EXPIRE of the owner record), and the chat path appends twice per turn (the user turn,
 * then the assistant turn). The script does the same steps in the same order in one {@code
 * EVALSHA}, which is also atomic: two concurrent appends to one session can no longer interleave
 * between the push and the trim.
 */
final class SessionAppendScript {

    private SessionAppendScript() {}

    /**
     * Which storage steps the append runs after the RPUSH; mirrors the branches of the legacy path.
     */
    enum Mode {
        /**
         * No live summariser: plain {@code LTRIM} to the newest entries (the pre-summary
         * behaviour).
         */
        PLAIN_TRIM("0"),
        /** Summariser on, no tenant context: marker-aware trim, no fold claim. */
        MARKER_AWARE_TRIM("1"),
        /**
         * Summariser on, tenant known: claim the out-of-window batch, then the marker-aware trim.
         */
        CLAIM_AND_TRIM("2");

        final String arg;

        Mode(String arg) {
            this.arg = arg;
        }
    }

    /**
     * Atomically claims the next batch of out-of-window turns to fold. KEYS[1] = history list,
     * KEYS[2] = fold marker; ARGV[1] = fold window (turns), ARGV[2] = minimum batch, ARGV[3] = TTL
     * seconds.
     *
     * <p>Reads the marker (clamped into {@code [0, LLEN]}), and if at least ARGV[2] entries lie
     * between it and the start of the window, returns them and advances the marker to the window
     * start - all in one script, so two concurrent appends cannot both claim the same batch.
     * Otherwise returns an empty list and only refreshes the marker's TTL (a marker that expired
     * before its list would make the next fold re-summarise turns the summary already covers).
     */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> FOLD_CLAIM_SCRIPT =
            new DefaultRedisScript<>(
                    """
local size = redis.call('LLEN', KEYS[1])
local folded = tonumber(redis.call('GET', KEYS[2]) or '0') or 0
if folded > size then folded = size end
if folded < 0 then folded = 0 end
local target = size - tonumber(ARGV[1])
if target - folded < tonumber(ARGV[2]) then
  if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
  return {}
end
local items = redis.call('LRANGE', KEYS[1], folded, target - 1)
redis.call('SET', KEYS[2], tostring(target), 'EX', ARGV[3])
return items
""",
                    List.class);

    /**
     * The storage trim for sessions with a live summariser: LTRIMs the list to the newest ARGV[1]
     * entries and, in the same atomic step, shifts the fold marker down by however many entries
     * were removed (floored at zero). KEYS[1] = history list, KEYS[2] = fold marker; ARGV[2] = TTL
     * seconds. Returns the number of entries dropped.
     */
    static final RedisScript<Long> TRIM_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local size = redis.call('LLEN', KEYS[1])
                    local drop = size - tonumber(ARGV[1])
                    if drop <= 0 then return 0 end
                    redis.call('LTRIM', KEYS[1], drop, -1)
                    local cur = redis.call('GET', KEYS[2])
                    if cur then
                      local f = (tonumber(cur) or 0) - drop
                      if f < 0 then f = 0 end
                      redis.call('SET', KEYS[2], tostring(f), 'EX', ARGV[2])
                    end
                    return drop
                    """,
                    Long.class);

    /**
     * RPUSH + [fold claim] + trim + EXPIRE + owner record, in the order the legacy path ran them.
     *
     * <p>KEYS: [1] history list, [2] fold marker, [3] owner record. ARGV: [1] turn JSON, [2] max
     * stored entries, [3] TTL seconds, [4] mode ({@link Mode#arg}), [5] fold window, [6] minimum
     * fold batch, [7] owner value or the empty string for "do not record an owner". Returns the
     * claimed batch (the serialized turns that left the replay window), empty unless mode is {@link
     * Mode#CLAIM_AND_TRIM}.
     */
    @SuppressWarnings("rawtypes")
    static final RedisScript<List> APPEND_SCRIPT =
            new DefaultRedisScript<>(
                    """
redis.call('RPUSH', KEYS[1], ARGV[1])
local mode = ARGV[4]
local claimed = {}
if mode == '2' then
  local size = redis.call('LLEN', KEYS[1])
  local folded = tonumber(redis.call('GET', KEYS[2]) or '0') or 0
  if folded > size then folded = size end
  if folded < 0 then folded = 0 end
  local target = size - tonumber(ARGV[5])
  if target - folded < tonumber(ARGV[6]) then
    if redis.call('EXISTS', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
  else
    claimed = redis.call('LRANGE', KEYS[1], folded, target - 1)
    redis.call('SET', KEYS[2], tostring(target), 'EX', ARGV[3])
  end
end
if mode == '0' then
  redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
else
  local size = redis.call('LLEN', KEYS[1])
  local drop = size - tonumber(ARGV[2])
  if drop > 0 then
    redis.call('LTRIM', KEYS[1], drop, -1)
    local cur = redis.call('GET', KEYS[2])
    if cur then
      local f = (tonumber(cur) or 0) - drop
      if f < 0 then f = 0 end
      redis.call('SET', KEYS[2], tostring(f), 'EX', ARGV[3])
    end
  end
end
redis.call('EXPIRE', KEYS[1], ARGV[3])
if ARGV[7] ~= '' then
  if not redis.call('SET', KEYS[3], ARGV[7], 'NX', 'EX', ARGV[3]) then
    redis.call('EXPIRE', KEYS[3], ARGV[3])
  end
end
return claimed
""",
                    List.class);

    /**
     * Runs {@link #APPEND_SCRIPT}: exactly one Redis round trip. A null or empty {@code owner}
     * means no owner record.
     */
    @SuppressWarnings("unchecked")
    static List<Object> append(
            StringRedisTemplate redis,
            String listKey,
            String markerKey,
            String ownerKey,
            String turnJson,
            int maxStoredEntries,
            long ttlSeconds,
            Mode mode,
            int foldWindowTurns,
            int foldBatch,
            String owner) {
        return redis.execute(
                APPEND_SCRIPT,
                List.of(listKey, markerKey, ownerKey),
                turnJson,
                Integer.toString(maxStoredEntries),
                Long.toString(ttlSeconds),
                mode.arg,
                Integer.toString(foldWindowTurns),
                Integer.toString(foldBatch),
                owner == null ? "" : owner);
    }
}
