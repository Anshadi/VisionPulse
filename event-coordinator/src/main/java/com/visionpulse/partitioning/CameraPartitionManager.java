package com.visionpulse.partitioning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionpulse.config.AppProperties;
import com.visionpulse.consensus.ZookeeperLeaderElection;
import com.visionpulse.consensus.ZookeeperNodeRegistry;
import jakarta.annotation.PostConstruct;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class CameraPartitionManager {

    private static final Logger log = LoggerFactory.getLogger(CameraPartitionManager.class);

    private final AppProperties appProperties;
    private final ZookeeperLeaderElection leaderElection;
    private final ZookeeperNodeRegistry nodeRegistry;
    private final Optional<CuratorFramework> curatorOptional;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final List<String> myAssignedCameras = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> globalPartitionMap = new ConcurrentHashMap<>();
    private CuratorCache partitionsCache;

    @Autowired
    public CameraPartitionManager(
            AppProperties appProperties,
            ZookeeperLeaderElection leaderElection,
            ZookeeperNodeRegistry nodeRegistry,
            Optional<CuratorFramework> curatorOptional) {
        this.appProperties = appProperties;
        this.leaderElection = leaderElection;
        this.nodeRegistry = nodeRegistry;
        this.curatorOptional = curatorOptional;
    }

    @PostConstruct
    public void init() {
        // Initial fallback assignment: all cameras assigned locally if standalone
        myAssignedCameras.addAll(appProperties.getManagedCameras());
        globalPartitionMap.put("node-" + appProperties.getNodeId(), new ArrayList<>(appProperties.getManagedCameras()));

        // Hook into membership & leadership
        nodeRegistry.addMembershipListener(activeNodes -> {
            if (leaderElection.isLeader()) {
                rebalancePartitions(activeNodes);
            }
        });

        leaderElection.onLeaderElected(() -> {
            log.info("[LEADER ACTION] Leader elected. Triggering immediate cluster partition rebalance.");
            rebalancePartitions(nodeRegistry.getActiveNodes());
        });

        // Setup partition cache watcher
        if (curatorOptional.isPresent() && curatorOptional.get().getZookeeperClient().isConnected()) {
            CuratorFramework client = curatorOptional.get();
            try {
                String partitionsBasePath = appProperties.getZookeeper().getBasePath() + "/partitions";
                if (client.checkExists().forPath(partitionsBasePath) == null) {
                    client.create().creatingParentsIfNeeded().forPath(partitionsBasePath, new byte[0]);
                }

                partitionsCache = CuratorCache.build(client, partitionsBasePath);
                partitionsCache.listenable().addListener(CuratorCacheListener.builder()
                        .forChanges((oldNode, newNode) -> syncLocalPartitions())
                        .forCreates(child -> syncLocalPartitions())
                        .forDeletes(child -> syncLocalPartitions())
                        .build());
                partitionsCache.start();
                log.info("Started ZooKeeper Partition Watcher on {}", partitionsBasePath);
            } catch (Exception e) {
                log.warn("Failed to initialize ZooKeeper Partition cache: {}", e.getMessage());
            }
        }
    }

    public synchronized void rebalancePartitions(List<String> activeNodes) {
        if (activeNodes.isEmpty()) {
            activeNodes = List.of("node-" + appProperties.getNodeId());
        }

        List<String> sortedNodes = new ArrayList<>(activeNodes);
        Collections.sort(sortedNodes);
        List<String> allCameras = appProperties.getManagedCameras();

        Map<String, List<String>> newAssignments = new HashMap<>();
        for (String node : sortedNodes) {
            newAssignments.put(node, new ArrayList<>());
        }

        // Round-robin distribution
        for (int i = 0; i < allCameras.size(); i++) {
            String node = sortedNodes.get(i % sortedNodes.size());
            newAssignments.get(node).add(allCameras.get(i));
        }

        log.info("===============================================================");
        log.info(">>> [PARTITION REBALANCE] Active Nodes: {}, Distribution: {} <<<", sortedNodes.size(), newAssignments);
        log.info("===============================================================");

        globalPartitionMap.clear();
        globalPartitionMap.putAll(newAssignments);

        // Publish to ZooKeeper
        if (curatorOptional.isPresent() && curatorOptional.get().getZookeeperClient().isConnected()) {
            CuratorFramework client = curatorOptional.get();
            String partitionsBasePath = appProperties.getZookeeper().getBasePath() + "/partitions";

            for (Map.Entry<String, List<String>> entry : newAssignments.entrySet()) {
                String nodePath = partitionsBasePath + "/" + entry.getKey();
                try {
                    byte[] data = objectMapper.writeValueAsBytes(entry.getValue());
                    if (client.checkExists().forPath(nodePath) != null) {
                        client.setData().forPath(nodePath, data);
                    } else {
                        client.create().withMode(CreateMode.EPHEMERAL).forPath(nodePath, data);
                    }
                } catch (Exception e) {
                    log.warn("Failed to write partition data for {}: {}", entry.getKey(), e.getMessage());
                }
            }
        }

        syncLocalPartitions();
    }

    private void syncLocalPartitions() {
        String myNodeKey = "node-" + appProperties.getNodeId();
        if (curatorOptional.isPresent() && curatorOptional.get().getZookeeperClient().isConnected()) {
            try {
                String myPartitionPath = appProperties.getZookeeper().getBasePath() + "/partitions/" + myNodeKey;
                CuratorFramework client = curatorOptional.get();
                if (client.checkExists().forPath(myPartitionPath) != null) {
                    byte[] data = client.getData().forPath(myPartitionPath);
                    if (data != null && data.length > 0) {
                        List<String> assigned = objectMapper.readValue(data, new TypeReference<List<String>>() {});
                        myAssignedCameras.clear();
                        myAssignedCameras.addAll(assigned);
                        log.info(">>> [PARTITION SYNC] Node-{} updated assigned cameras: {} <<<", appProperties.getNodeId(), myAssignedCameras);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Error reading local partition data: {}", e.getMessage());
            }
        }

        if (globalPartitionMap.containsKey(myNodeKey)) {
            myAssignedCameras.clear();
            myAssignedCameras.addAll(globalPartitionMap.get(myNodeKey));
        }
    }

    public boolean isCameraOwnedByMe(String cameraId) {
        return myAssignedCameras.contains(cameraId);
    }

    public List<String> getMyAssignedCameras() {
        return Collections.unmodifiableList(myAssignedCameras);
    }

    public Map<String, List<String>> getGlobalPartitionMap() {
        return Collections.unmodifiableMap(globalPartitionMap);
    }
}
