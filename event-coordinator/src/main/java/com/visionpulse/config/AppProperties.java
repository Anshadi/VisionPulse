package com.visionpulse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "visionpulse")
public class AppProperties {
    private int nodeId = 1;
    private int datacenterId = 1;
    private ZookeeperProperties zookeeper = new ZookeeperProperties();
    private CorrelationProperties correlation = new CorrelationProperties();
    private List<String> managedCameras = List.of("CAM-01", "CAM-02", "CAM-03", "CAM-04", "CAM-05", "CAM-06");

    @Data
    public static class ZookeeperProperties {
        private String connectString = "localhost:2181";
        private int sessionTimeoutMs = 10000;
        private int connectionTimeoutMs = 5000;
        private String basePath = "/visionpulse";
    }

    @Data
    public static class CorrelationProperties {
        private long windowMs = 800;
        private int minCamerasForIncident = 2;
    }
}
