package com.visionpulse.idgen;

import com.visionpulse.config.AppProperties;
import com.visionpulse.model.IdResponse;
import com.visionpulse.model.LatencyMetricsDto;
import com.visionpulse.model.ParsedIdDto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.SingleWriterRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AdaptiveIdManager {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveIdManager.class);

    private final AppProperties appProperties;
    private final SnowflakeStrategy snowflakeStrategy;
    private final RedisSegmentStrategy redisSegmentStrategy;
    private final Counter idSuccessCounter;
    private final Counter idFallbackCounter;
    private final Timer idTimer;
    private final SingleWriterRecorder latencyRecorder = new SingleWriterRecorder(1, 100_000_000L, 3);
    private final AtomicLong totalGeneratedCount = new AtomicLong(0);

    @Autowired
    public AdaptiveIdManager(
            AppProperties appProperties,
            SnowflakeStrategy snowflakeStrategy,
            RedisSegmentStrategy redisSegmentStrategy,
            Counter idGenerationSuccessCounter,
            Counter idGenerationFallbackCounter,
            Timer idGenerationTimer) {
        this.appProperties = appProperties;
        this.snowflakeStrategy = snowflakeStrategy;
        this.redisSegmentStrategy = redisSegmentStrategy;
        this.idSuccessCounter = idGenerationSuccessCounter;
        this.idFallbackCounter = idGenerationFallbackCounter;
        this.idTimer = idGenerationTimer;
    }

    public Mono<IdResponse> generateId() {
        return Mono.fromSupplier(this::generateIdSync);
    }

    public long generateRawId() {
        return generateIdSync().getId();
    }

    public IdResponse generateIdSync() {
        long startNanos = System.nanoTime();
        long id;
        String strategyUsed;

        try {
            id = snowflakeStrategy.generateId();
            strategyUsed = snowflakeStrategy.getStrategyName();
            idSuccessCounter.increment();
        } catch (Exception e) {
            log.warn("Snowflake generation failed, falling back to Redis Segment Strategy: {}", e.getMessage());
            idFallbackCounter.increment();
            id = redisSegmentStrategy.generateId();
            strategyUsed = redisSegmentStrategy.getStrategyName();
        }

        long elapsedNanos = System.nanoTime() - startNanos;
        long elapsedMicros = Math.max(1, elapsedNanos / 1000);

        idTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
        latencyRecorder.recordValue(Math.min(elapsedMicros, 99_999_999L));
        totalGeneratedCount.incrementAndGet();

        ParsedIdDto parsed = IdPacker.unpack(id);

        return IdResponse.builder()
                .id(id)
                .strategy(strategyUsed)
                .latencyMicros(elapsedMicros)
                .nodeId(appProperties.getNodeId())
                .datacenterId(appProperties.getDatacenterId())
                .timestamp(parsed.getTimestamp())
                .sequence(parsed.getSequence())
                .build();
    }

    public ParsedIdDto decodeId(long id) {
        return IdPacker.unpack(id);
    }

    public LatencyMetricsDto getLatencyMetrics() {
        Histogram histogram = latencyRecorder.getIntervalHistogram();
        return LatencyMetricsDto.builder()
                .count(histogram.getTotalCount())
                .meanMicros(histogram.getMean())
                .p50Micros(histogram.getValueAtPercentile(50.0))
                .p90Micros(histogram.getValueAtPercentile(90.0))
                .p99Micros(histogram.getValueAtPercentile(99.0))
                .p999Micros(histogram.getValueAtPercentile(99.9))
                .maxMicros(histogram.getMaxValue())
                .totalGenerated(totalGeneratedCount.get())
                .build();
    }
}
