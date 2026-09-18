package dev.payvero.transfer;

import dev.payvero.api.PayveroMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
public class TransferRateLimiter {

    private static final String SCRIPT = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - windowMillis)
            if redis.call('ZCARD', key) >= limit then
                return 0
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, windowMillis)
            return 1
            """;

    private final StringRedisTemplate redis;
    private final TransferProperties properties;
    private final Clock clock;
    private final RedisScript<Long> admitScript;

    private final PayveroMetrics metrics;

    public TransferRateLimiter(StringRedisTemplate redis, TransferProperties properties, Clock clock,
                               PayveroMetrics metrics) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
        this.admitScript = new DefaultRedisScript<>(SCRIPT, Long.class);
    }

    public void requireSlot(UUID userId) {
        long now = clock.millis();
        Long admitted = redis.execute(
                admitScript,
                List.of(key(userId)),
                Long.toString(now),
                Long.toString(properties.rateLimitWindow().toMillis()),
                Integer.toString(properties.rateLimitPerWindow()),
                UUID.randomUUID().toString());

        if (admitted == null || admitted == 0L) {
            metrics.rateLimitRejected();
            throw new RateLimitExceededException();
        }
    }

    private static String key(UUID userId) {
        return "payvero:ratelimit:transfer:" + userId;
    }
}
