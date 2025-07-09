package com.example.gateway.controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.gateway.config.GatewayConfig;
import com.example.gateway.health.HealthMonitor;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/gateway/management")
@RequiredArgsConstructor
public class GatewayManagementController {
    private final GatewayConfig gatewayConfig;
    private final HealthMonitor healthMonitor;

    @GetMapping("/health")
    public Mono<Map<String, Object>> getOverallHealth() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> services = new HashMap<>();

        gatewayConfig.getServices().forEach(service -> {
            Map<String, Object> serviceHealth = new HashMap<>();
            service.getInstances().forEach(instance -> {
                HealthMonitor.ServiceHealth health = healthMonitor.getServiceHealth(instance.getId());

                if (health != null) {
                    Map<String, Object> instanceHealth = new HashMap<>();
                    instanceHealth.put("healthy", health.isHealthy());
                    instanceHealth.put("successRate", health.getSuccessRate());
                    instanceHealth.put("consecutiveFailures", health.getConsecutiveFailure().get());
                    instanceHealth.put("lastCheck", health.getLastCheckTime());
                    serviceHealth.put(instance.getId(), instanceHealth);
                }
            });
            services.put(service.getId(), serviceHealth);
        });

        response.put("services", services);
        response.put("timestamp", LocalDateTime.now());

        return Mono.just(response);
    }

    @PostMapping("services/{serviceId}/toggle")
    public Mono<Map<String, Object>> toggleService(@PathVariable String serviceId, @RequestParam boolean active) {
        Map<String, Object> response = new HashMap<>();

        gatewayConfig.getServices().stream()
            .filter(service -> service.getId().equals(serviceId))
            .findFirst()
            .ifPresent(service -> {
                service.getInstances().forEach(instance -> instance.setActive(active));
                response.put("status", "updated");
                response.put("serviceId", serviceId);
                response.put("active", active);
            });
        
        if (response.isEmpty()) {
            response.put("error", "Service not found");
        }

        return Mono.just(response);
    }
}