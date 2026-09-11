package com.visionpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdResponse {
    private long id;
    private String strategy;
    private long latencyMicros;
    private int nodeId;
    private int datacenterId;
    private long timestamp;
    private long sequence;
}
