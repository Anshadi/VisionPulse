package com.visionpulse.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Counter idGenerationSuccessCounter(MeterRegistry registry) {
        return Counter.builder("visionpulse.id.generation.success")
                .description("Total successfully generated distributed IDs")
                .register(registry);
    }

    @Bean
    public Counter idGenerationFallbackCounter(MeterRegistry registry) {
        return Counter.builder("visionpulse.id.generation.fallback")
                .description("Total times ID generator fell back to secondary strategy")
                .register(registry);
    }

    @Bean
    public Counter incidentCreatedCounter(MeterRegistry registry) {
        return Counter.builder("visionpulse.incidents.created")
                .description("Total multi-camera correlated incidents created")
                .register(registry);
    }

    @Bean
    public Timer idGenerationTimer(MeterRegistry registry) {
        return Timer.builder("visionpulse.id.generation.latency")
                .description("Latency distribution of ID generation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }
}
