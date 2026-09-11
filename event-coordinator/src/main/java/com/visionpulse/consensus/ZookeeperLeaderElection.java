package com.visionpulse.consensus;

import com.visionpulse.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

@Component
public class ZookeeperLeaderElection {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperLeaderElection.class);

    private final AppProperties appProperties;
    private final Optional<CuratorFramework> curatorFrameworkOptional;
    private LeaderLatch leaderLatch;
    private volatile boolean isLeader = false;
    private final List<Runnable> leaderElectedCallbacks = new CopyOnWriteArrayList<>();
    private final List<Runnable> leaderRevokedCallbacks = new CopyOnWriteArrayList<>();

    @Autowired
    public ZookeeperLeaderElection(AppProperties appProperties, Optional<CuratorFramework> curatorFramework) {
        this.appProperties = appProperties;
        this.curatorFrameworkOptional = curatorFramework;
    }

    @PostConstruct
    public void init() {
        if (curatorFrameworkOptional.isPresent() && curatorFrameworkOptional.get().getZookeeperClient().isConnected()) {
            try {
                String leaderPath = appProperties.getZookeeper().getBasePath() + "/leader";
                String id = "node-" + appProperties.getNodeId() + "-" + System.currentTimeMillis();
                leaderLatch = new LeaderLatch(curatorFrameworkOptional.get(), leaderPath, id);
                leaderLatch.addListener(new LeaderLatchListener() {
                    @Override
                    public void isLeader() {
                        isLeader = true;
                        log.info("===============================================================");
                        log.info(">>> [VISIONPULSE CONSENSUS] Node-{} ELECTED CLUSTER LEADER! <<<", appProperties.getNodeId());
                        log.info("===============================================================");
                        leaderElectedCallbacks.forEach(Runnable::run);
                    }

                    @Override
                    public void notLeader() {
                        isLeader = false;
                        log.info(">>> [VISIONPULSE CONSENSUS] Node-{} is in STANDBY/WORKER role <<<", appProperties.getNodeId());
                        leaderRevokedCallbacks.forEach(Runnable::run);
                    }
                });
                leaderLatch.start();
                log.info("Started ZooKeeper LeaderLatch on {}", leaderPath);
            } catch (Exception e) {
                log.warn("Failed to initialize ZooKeeper LeaderLatch: {}. Defaulting to standalone leader.", e.getMessage());
                isLeader = true;
            }
        } else {
            isLeader = true;
            log.info("Running in standalone single-instance mode (Acting Leader).");
        }
    }

    public void onLeaderElected(Runnable callback) {
        leaderElectedCallbacks.add(callback);
    }

    public void onLeaderRevoked(Runnable callback) {
        leaderRevokedCallbacks.add(callback);
    }

    @PreDestroy
    public void shutdown() {
        if (leaderLatch != null) {
            try {
                leaderLatch.close();
            } catch (Exception e) {
                log.warn("Error closing LeaderLatch: {}", e.getMessage());
            }
        }
    }

    public boolean isLeader() {
        if (leaderLatch != null && leaderLatch.getState() == LeaderLatch.State.STARTED) {
            return leaderLatch.hasLeadership();
        }
        return isLeader;
    }

    public String getLeaderId() {
        if (leaderLatch != null && leaderLatch.getState() == LeaderLatch.State.STARTED) {
            try {
                return leaderLatch.getLeader().getId();
            } catch (Exception e) {
                return "unknown";
            }
        }
        return "local-node-" + appProperties.getNodeId();
    }
}
