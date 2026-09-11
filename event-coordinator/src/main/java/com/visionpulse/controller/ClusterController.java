package com.visionpulse.controller;

import com.visionpulse.model.ClusterNodeInfo;
import com.visionpulse.model.LatencyMetricsDto;
import com.visionpulse.partitioning.CameraPartitionManager;
import com.visionpulse.service.ClusterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class ClusterController {

    private final ClusterService clusterService;
    private final CameraPartitionManager partitionManager;

    @Autowired
    public ClusterController(ClusterService clusterService, CameraPartitionManager partitionManager) {
        this.clusterService = clusterService;
        this.partitionManager = partitionManager;
    }

    @GetMapping("/cluster/status")
    public Mono<ClusterNodeInfo> getClusterStatus() {
        return Mono.fromSupplier(clusterService::getClusterStatus);
    }

    @GetMapping("/cluster/partitions")
    public Map<String, List<String>> getPartitions() {
        return partitionManager.getGlobalPartitionMap();
    }

    @GetMapping("/metrics/latency")
    public Mono<LatencyMetricsDto> getLatencyMetrics() {
        return Mono.fromSupplier(clusterService::getLatencyMetrics);
    }
}
