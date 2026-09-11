package com.visionpulse.idgen;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-buffer Redis Segment ID Strategy.
 * Fetches blocks of 1,000 IDs in atomic increments and buffers locally.
 */
@Component
public class RedisSegmentStrategy implements IdGeneratorStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisSegmentStrategy.class);
    private static final String REDIS_SEGMENT_KEY = "visionpulse:id:segment";
    private static final long SEGMENT_STEP = 1000L;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final AtomicLong currentId = new AtomicLong(0);
    private final AtomicLong maxId = new AtomicLong(0);
    private final AtomicBoolean isLoadingNextSegment = new AtomicBoolean(false);
    private volatile boolean isAvailable = false;

    @Autowired
    public RedisSegmentStrategy(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void init() {
        try {
            fetchNewSegment();
            isAvailable = true;
        } catch (Exception e) {
            log.warn("Redis Segment Strategy unavailable initially: {}", e.getMessage());
            isAvailable = false;
        }
    }

    @Override
    public long generateId() {
        long id = currentId.incrementAndGet();
        if (id <= maxId.get()) {
            if (maxId.get() - id < (SEGMENT_STEP / 5) && !isLoadingNextSegment.get()) {
                triggerAsyncPreload();
            }
            return id;
        }

        synchronized (this) {
            if (currentId.get() > maxId.get()) {
                fetchNewSegment();
            }
            return currentId.incrementAndGet();
        }
    }

    private void fetchNewSegment() {
        try {
            Long newMax = redisTemplate.opsForValue().increment(REDIS_SEGMENT_KEY, SEGMENT_STEP).block();
            if (newMax != null) {
                long newMin = newMax - SEGMENT_STEP;
                currentId.set(newMin);
                maxId.set(newMax);
                isAvailable = true;
            }
        } catch (Exception e) {
            isAvailable = false;
            throw new RuntimeException("Failed to allocate segment from Redis", e);
        }
    }

    private void triggerAsyncPreload() {
        if (isLoadingNextSegment.compareAndSet(false, true)) {
            redisTemplate.opsForValue().increment(REDIS_SEGMENT_KEY, SEGMENT_STEP)
                    .doFinally(signal -> isLoadingNextSegment.set(false))
                    .subscribe();
        }
    }

    @Override
    public String getStrategyName() {
        return "REDIS_SEGMENT";
    }

    @Override
    public boolean isAvailable() {
        return isAvailable;
    }
}
