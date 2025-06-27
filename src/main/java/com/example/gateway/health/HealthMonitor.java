package com.example.gateway.health;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class HealthMonitor {
    private final WebClient webClient;
    private final ConcurrentHashMap<String, ServiceHealth> serviceHealthMap;

    public HealthMonitor() {
        this.webClient = WebClient.builder().build();
        this.serviceHealthMap = new ConcurrentHashMap<>();
    }

    public Mono<ServiceHealth> checkHealth(String serviceID, String url, String endPoint) {
        return webClient.get()
        .uri(url + endPoint)
        .retrieve()
        .toBodilessEntity()
        .map(response -> {
            ServiceHealth health = getOrCreateHealth(serviceID);
            health.recordSuccess();
            log.debug("Health check successful for service: {}", serviceID);
            return health;
        })
        .onErrorResume(error -> {
            ServiceHealth health = getOrCreateHealth(serviceID);
            health.recordFailure();
            log.debug("Health check failed for service: {}", serviceID);
            return Mono.just(health);
        });
    }

    private ServiceHealth getOrCreateHealth(String serviceID) {
        return serviceHealthMap.computeIfAbsent(serviceID, k -> new ServiceHealth(k));
    }

    public boolean isServiceHealthy(String serviceID) {
        ServiceHealth health = serviceHealthMap.get(serviceID);
        return health != null && health.isHealthy();
    }

    public ServiceHealth getServiceHealth(String serviceID) {
        return serviceHealthMap.get(serviceID);
    }

    @Data
    public static class ServiceHealth {
        private final String serviceID;
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong consecutiveFailure = new AtomicLong(0);
        private volatile LocalDateTime lastCheckTime;
        private volatile boolean healthy = true;
        private volatile double responseTime = 0.0;

        public ServiceHealth(String serviceID) {
            this.serviceID = serviceID;
            this.lastCheckTime = LocalDateTime.now();
        }
        public void recordSuccess() {
            successCount.incrementAndGet();
            consecutiveFailure.set(0);
            healthy = true;
            lastCheckTime = LocalDateTime.now();
        }
        public void recordFailure() {
            failureCount.incrementAndGet();
            consecutiveFailure.incrementAndGet();
            healthy = consecutiveFailure.get() < 3;
            lastCheckTime = LocalDateTime.now();
        }
        public double getSuccessRate() {
            long total = successCount.get() + failureCount.get();
            return total > 0 ? (double) successCount.get() / total : 1.0;
        }
        public boolean isHealthy() {
            return healthy && consecutiveFailure.get() < 3;
        }
    }
}