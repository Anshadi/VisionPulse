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
public class ClusterNodeInfo {
    private int nodeId;
    private String address;
    private int port;
    private String role; // "LEADER", "STANDBY", "WORKER"
    private boolean isLeader;
    private String leaderId;
    private List<String> assignedCameras;
    private long activeNodesCount;
    private String uptime;
    private String status; // "HEALTHY", "DEGRADED", "FAILOVER_IN_PROGRESS"
}
