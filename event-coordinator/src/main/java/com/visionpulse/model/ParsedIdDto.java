package com.visionpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedIdDto {
    private long rawId;
    private long timestamp;
    private String formattedTimestamp;
    private int datacenterId;
    private int workerId;
    private long sequence;
    private String binaryString;
}
