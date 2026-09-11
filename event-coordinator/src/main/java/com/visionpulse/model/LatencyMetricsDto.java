package com.visionpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LatencyMetricsDto {
    private long count;
    private double meanMicros;
    private double p50Micros;
    private double p90Micros;
    private double p99Micros;
    private double p999Micros;
    private double maxMicros;
    private long totalGenerated;
}
