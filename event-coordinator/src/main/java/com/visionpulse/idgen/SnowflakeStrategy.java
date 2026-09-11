package com.visionpulse.idgen;

import com.visionpulse.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 64-bit Twitter Snowflake ID Generator:
 * 1 bit sign | 41 bits timestamp (ms) | 5 bits datacenterId | 5 bits workerNodeId | 12 bits sequence
 * Generates strictly monotonic, K-ordered distributed IDs without network coordination.
 */
@Component
public class SnowflakeStrategy implements IdGeneratorStrategy {

    private static final long CUSTOM_EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC

    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private final long workerId;
    private final long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    @Autowired
    public SnowflakeStrategy(AppProperties appProperties) {
        this.workerId = appProperties.getNodeId() & MAX_WORKER_ID;
        this.datacenterId = appProperties.getDatacenterId() & MAX_DATACENTER_ID;
    }

    @Override
    public synchronized long generateId() {
        long currentTimestamp = timeGen();

        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            if (offset <= 5) {
                try {
                    Thread.sleep(offset << 1);
                    currentTimestamp = timeGen();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                throw new IllegalStateException(String.format("Clock moved backwards. Refusing to generate id for %d ms", offset));
            }
        }

        if (lastTimestamp == currentTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                currentTimestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - CUSTOM_EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }

    @Override
    public String getStrategyName() {
        return "SNOWFLAKE";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
