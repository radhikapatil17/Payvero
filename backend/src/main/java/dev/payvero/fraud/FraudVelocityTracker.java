package dev.payvero.fraud;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A sliding window log per user, held in a Redis sorted set scored by timestamp.
 * <p>
 * Sliding rather than a fixed bucket, and the difference is the whole point. A
 * counter keyed by the current minute resets on the minute boundary, so five
 * transfers at 10:59:59 and five more at 11:00:01 look like two quiet minutes
 * while actually being ten transfers in two seconds. Trimming by relative age
 * means the window always covers the last N milliseconds, wherever the clock
 * happens to be.
 * <p>
 * Trim, record and total run as one Lua script so the answer describes a single
 * consistent moment rather than three separate round trips.
 */
@Component
public class FraudVelocityTracker {

    /** Members are "transferId|amount"; the id keeps a redelivery from counting twice. */
    private static final String SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])
            local member = ARGV[3]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - windowMillis)
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, windowMillis)

            local members = redis.call('ZRANGE', key, 0, -1)
            local total = 0
            for i = 1, #members do
                local amount = string.match(members[i], '|(%d+)$')
                if amount then
                    total = total + tonumber(amount)
                end
            end
            return { #members, total }
            """;

    private final StringRedisTemplate redis;
    private final FraudProperties properties;
    private final Clock clock;
    private final RedisScript<List> observeScript;

    public FraudVelocityTracker(StringRedisTemplate redis, FraudProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.observeScript = new DefaultRedisScript<>(SCRIPT, List.class);
    }

    public VelocitySnapshot observe(UUID userId, UUID transferId, long amount) {
        return observeAt(userId, transferId, amount, clock.instant());
    }

    /**
     * Package-private overload taking the instant explicitly, so a test can walk
     * time across a window boundary without sleeping. Production always goes
     * through {@link #observe} and the injected clock.
     */
    @SuppressWarnings("unchecked")
    VelocitySnapshot observeAt(UUID userId, UUID transferId, long amount, Instant at) {
        List<Long> result = (List<Long>) redis.execute(
                observeScript,
                List.of(key(userId)),
                Long.toString(at.toEpochMilli()),
                Long.toString(properties.window().toMillis()),
                transferId + "|" + amount);

        if (result == null || result.size() < 2) {
            return new VelocitySnapshot(0, 0);
        }
        return new VelocitySnapshot(result.get(0).intValue(), result.get(1));
    }

    private static String key(UUID userId) {
        return "payvero:fraud:velocity:" + userId;
    }
}
