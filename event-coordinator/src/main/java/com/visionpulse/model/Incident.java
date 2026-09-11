package com.visionpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Incident {
    private long incidentId;
    private String zoneId;
    private String incidentType;
    private List<String> triggeringCameras;
    private int eventCount;
    private long firstEventTimestamp;
    private long lastEventTimestamp;
    private long durationMs;
    private double confidenceScore;
    private String status; // "ACTIVE", "CORRELATED", "RESOLVED"
    private int coordinatorNodeId;
    private List<CameraEvent> correlatedEvents;
}
