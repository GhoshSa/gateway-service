package com.example.gateway.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "gateway")
public class GatewayConfig {
    private List<ServiceConfig> services;
    private HealthCheckConfig healthCheck = new HealthCheckConfig();
    private PredictionConfig prediction = new PredictionConfig();

    @Data
    public static class ServiceConfig {
        private String id;
        private String name;
        private String path;
        private List<ServiceInstance> instances;
        private FallbackStrategy fallbackStrategy;
        private Map<String, String> metaData;
        private int priority = 1;
        private boolean enablePrediction = true;

    }

    @Data
    public static class ServiceInstance {
        private String id;
        private String url;
        private int weight = 100;
        private boolean active = true;
        private String environment;
    }

    @Data
    public static class HealthCheckConfig {
        private int intervalSeconds = 30;
        private int timeoutSeconds = 5;
        private int retryCount = 3;
        private String healthEndpoint = "/health";
    }

    @Data
    public static class PredictionConfig {
        private boolean enabled = true;
        private int windowSizeMinutes = 10;
        private double failureThreshold = 0.7;
        private int predictionIntervalSeconds = 60;
    }

    public enum FallbackStrategy {
        CIRCUIT_BREAKER,
        RETRY_WITH_BACKOFF,
        FAILOVER_INSTANCE,
        CACHED_RESPONSE,
        DEFAULT_RESPONSE,
        HYBRID
    }
}