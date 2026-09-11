package com.visionpulse.consensus;

import com.visionpulse.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class ZookeeperNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperNodeRegistry.class);

    private final AppProperties appProperties;
    private final Optional<CuratorFramework> curatorOptional;
    private CuratorCache nodesCache;
    private String nodeEphemeralPath;
    private final List<Consumer<List<String>>> membershipChangeListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public ZookeeperNodeRegistry(AppProperties appProperties, Optional<CuratorFramework> curatorOptional) {
        this.appProperties = appProperties;
        this.curatorOptional = curatorOptional;
    }

    @PostConstruct
    public void register() {
        if (curatorOptional.isPresent() && curatorOptional.get().getZookeeperClient().isConnected()) {
            CuratorFramework client = curatorOptional.get();
            try {
                String basePath = appProperties.getZookeeper().getBasePath();
                String nodesPath = basePath + "/nodes";

                if (client.checkExists().forPath(nodesPath) == null) {
                    client.create().creatingParentsIfNeeded().forPath(nodesPath, new byte[0]);
                }

                nodeEphemeralPath = nodesPath + "/node-" + appProperties.getNodeId();
                String payload = "{\"nodeId\":" + appProperties.getNodeId() + ",\"status\":\"UP\"}";

                if (client.checkExists().forPath(nodeEphemeralPath) != null) {
                    client.delete().forPath(nodeEphemeralPath);
                }

                client.create()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(nodeEphemeralPath, payload.getBytes(StandardCharsets.UTF_8));

                log.info("Registered ephemeral node in ZooKeeper: {}", nodeEphemeralPath);

                // Watch cluster membership
                nodesCache = CuratorCache.build(client, nodesPath);
                CuratorCacheListener listener = CuratorCacheListener.builder()
                        .forChanges((oldNode, newNode) -> notifyMembershipChange())
                        .forCreates(child -> notifyMembershipChange())
                        .forDeletes(child -> notifyMembershipChange())
                        .build();

                nodesCache.listenable().addListener(listener);
                nodesCache.start();

            } catch (Exception e) {
                log.warn("Failed to register node in ZooKeeper: {}", e.getMessage());
            }
        }
    }

    private void notifyMembershipChange() {
        List<String> activeNodes = getActiveNodes();
        log.info("[CLUSTER TOPOLOGY CHANGED] Active nodes ({}): {}", activeNodes.size(), activeNodes);
        membershipChangeListeners.forEach(listener -> listener.accept(activeNodes));
    }

    public void addMembershipListener(Consumer<List<String>> listener) {
        membershipChangeListeners.add(listener);
    }

    public List<String> getActiveNodes() {
        if (curatorOptional.isPresent() && curatorOptional.get().getZookeeperClient().isConnected()) {
            try {
                String nodesPath = appProperties.getZookeeper().getBasePath() + "/nodes";
                if (curatorOptional.get().checkExists().forPath(nodesPath) != null) {
                    return curatorOptional.get().getChildren().forPath(nodesPath);
                }
            } catch (Exception e) {
                log.warn("Error querying active nodes from ZooKeeper: {}", e.getMessage());
            }
        }
        return List.of("node-" + appProperties.getNodeId());
    }

    @PreDestroy
    public void unregister() {
        if (nodesCache != null) {
            nodesCache.close();
        }
        if (curatorOptional.isPresent() && nodeEphemeralPath != null) {
            try {
                curatorOptional.get().delete().quietly().forPath(nodeEphemeralPath);
                log.info("Unregistered node path {}", nodeEphemeralPath);
            } catch (Exception e) {
                log.warn("Error unregistering node: {}", e.getMessage());
            }
        }
    }
}
