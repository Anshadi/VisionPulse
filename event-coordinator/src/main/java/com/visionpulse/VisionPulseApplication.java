package com.visionpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.visionpulse.config.AppProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class VisionPulseApplication {
    public static void main(String[] args) {
        SpringApplication.run(VisionPulseApplication.class, args);
    }
}
