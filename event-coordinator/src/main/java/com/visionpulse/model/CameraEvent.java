package com.visionpulse.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraEvent {
    private long eventId;
    private String cameraId;
    private String zoneId;
    private String eventType;
    private double edgeDensity;
    private double movingEnergy;
    private double deltaRatio;
    private double motionVectorAngle;
    private double velocityMagnitude;
    private long frameSeq;
    private long detectedAt;
    private int processedByNodeId;
    private String strategyUsed;
    private String snapshotBase64;
}
