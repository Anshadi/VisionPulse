package com.visionpulse.config;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

@Configuration
public class ZookeeperConfig {

    private static final Logger log = LoggerFactory.getLogger(ZookeeperConfig.class);

    @Bean(destroyMethod = "close")
    public Optional<CuratorFramework> curatorFramework(AppProperties appProperties) {
        try {
            AppProperties.ZookeeperProperties zkProps = appProperties.getZookeeper();
            RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
            
            CuratorFramework client = CuratorFrameworkFactory.builder()
                    .connectString(zkProps.getConnectString())
                    .sessionTimeoutMs(zkProps.getSessionTimeoutMs())
                    .connectionTimeoutMs(zkProps.getConnectionTimeoutMs())
                    .retryPolicy(retryPolicy)
                    .build();

            client.start();
            boolean connected = client.blockUntilConnected(3, java.util.concurrent.TimeUnit.SECONDS);
            if (connected) {
                log.info("Successfully connected to ZooKeeper at {}", zkProps.getConnectString());
                return Optional.of(client);
            } else {
                log.warn("ZooKeeper not reachable within 3 seconds. Starting in standalone fallback mode.");
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("Failed to initialize ZooKeeper connection: {}. Running in standalone mode.", e.getMessage());
            return Optional.empty();
        }
    }
}
