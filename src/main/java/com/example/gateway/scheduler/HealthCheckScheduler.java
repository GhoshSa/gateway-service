package com.example.gateway.scheduler;

import com.example.gateway.prediction.FailurePredictionEngine;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.gateway.config.GatewayConfig;
import com.example.gateway.health.HealthMonitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@RequiredArgsConstructor
@Slf4j
public class HealthCheckScheduler {
    private final GatewayConfig gatewayConfig;
    private final HealthMonitor healthMonitor;
    private final FailurePredictionEngine predictionEngine;

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
            log.debug("Failure prediction is disabled by configuration.");
            return;
        }

        log.debug("Starting failure predictions...");

        Flux.fromIterable(gatewayConfig.getServices()).filter(GatewayConfig.ServiceConfig::isEnablePrediction).flatMap(service ->
            Mono.fromCallable(() -> predictionEngine.predictFailure(service.getId(), 5)).subscribeOn(Schedulers.boundedElastic()).onErrorResume(e -> {
                log.error("Prediction failed for service: {} - {}", service.getId(), e.getMessage());
                return Mono.empty();
            }).doOnNext(prediction -> {
                if (prediction.isActionRequired()) {
                    log.warn("Prediction alert for service {}: {} (risk: {})", prediction.getServiceId(), prediction.getReason(), String.format("%.2f", prediction.getRiskScore()));
                    triggerPreventiveActions(service, prediction);
                }
            })
        ).subscribe(
                null,
                error -> log.error("Overall failure prediction batch failed: {}", error.getMessage()),
                () -> log.debug("All failure predictions completed")
        );
    }

    private void triggerPreventiveActions(GatewayConfig.ServiceConfig service, FailurePredictionEngine.PredictionResult prediction) {
        log.info("Triggering preventive actions for service: {} (risk: {})", service.getId(), String.format("%.2f", prediction.getRiskScore()));
    }
}
