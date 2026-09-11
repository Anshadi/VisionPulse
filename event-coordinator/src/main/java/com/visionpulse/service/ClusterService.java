package com.visionpulse.service;

import com.visionpulse.config.AppProperties;
import com.visionpulse.consensus.ZookeeperLeaderElection;
import com.visionpulse.consensus.ZookeeperNodeRegistry;
import com.visionpulse.idgen.AdaptiveIdManager;
import com.visionpulse.model.ClusterNodeInfo;
import com.visionpulse.model.LatencyMetricsDto;
import com.visionpulse.partitioning.CameraPartitionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.List;

@Service
public class ClusterService {

    private final AppProperties appProperties;
    private final ZookeeperLeaderElection leaderElection;
    private final ZookeeperNodeRegistry nodeRegistry;
    private final CameraPartitionManager partitionManager;
    private final AdaptiveIdManager idManager;

    @Autowired
    public ClusterService(
            AppProperties appProperties,
            ZookeeperLeaderElection leaderElection,
            ZookeeperNodeRegistry nodeRegistry,
            CameraPartitionManager partitionManager,
            AdaptiveIdManager idManager) {
        this.appProperties = appProperties;
        this.leaderElection = leaderElection;
        this.nodeRegistry = nodeRegistry;
        this.partitionManager = partitionManager;
        this.idManager = idManager;
    }

    public ClusterNodeInfo getClusterStatus() {
        boolean isLeader = leaderElection.isLeader();
        List<String> activeNodes = nodeRegistry.getActiveNodes();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();

        return ClusterNodeInfo.builder()
                .nodeId(appProperties.getNodeId())
                .address("localhost")
                .port(8080 + appProperties.getNodeId())
                .role(isLeader ? "LEADER" : "WORKER")
                .isLeader(isLeader)
                .leaderId(leaderElection.getLeaderId())
                .assignedCameras(partitionManager.getMyAssignedCameras())
                .activeNodesCount(activeNodes.size())
                .uptime(formatUptime(uptimeMs))
                .status("HEALTHY")
                .build();
    }

    public LatencyMetricsDto getLatencyMetrics() {
        return idManager.getLatencyMetrics();
    }

    private String formatUptime(long uptimeMs) {
        long seconds = (uptimeMs / 1000) % 60;
        long minutes = (uptimeMs / (1000 * 60)) % 60;
        long hours = (uptimeMs / (1000 * 60 * 60));
        return String.format("%02dh:%02dm:%02ds", hours, minutes, seconds);
    }
}
