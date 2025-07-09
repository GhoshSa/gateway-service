package com.example.gateway.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.gateway.config.GatewayConfig;
import com.example.gateway.health.HealthMonitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
@Slf4j
public class HealthCheckScheduler {
    private final GatewayConfig gatewayConfig;
    private final HealthMonitor healthMonitor;

    @Scheduled(fixedRateString = "#{${gateway.health-check.interval-seconds:30} * 1000}")
    public void performHealthCheck() {
        log.debug("Starting scheduled health checks...");

        Flux.fromIterable(gatewayConfig.getServices()).flatMap(service -> 
            Flux.fromIterable(service.getInstances()).flatMap(instance -> 
                healthMonitor.checkHealth(instance.getId(), instance.getUrl(), gatewayConfig.getHealthCheck().getHealthEndpoint()).onErrorResume(error -> {
                    log.error("Health check failed for instance: {} - {}", instance.getId(), error.getMessage());
                    return Mono.empty();
                })
            )
        ).subscribe(
            health -> log.debug("Health check completed for: {}", health.getServiceID()),
            error -> log.error("Health check batch failed: {}", error.getMessage()),
            () -> log.debug("All health checks completed")
        );
    }

    @Scheduled(fixedRateString = "#{${gateway.self-healing.prediction.prediction-interval-seconds:60} * 1000}")
    public void performFailurePrediction() {
        if (!gatewayConfig.getPrediction().isEnabled()) {
            return;
        }

        log.debug("Starting failure predictions...");

        
    }
}
