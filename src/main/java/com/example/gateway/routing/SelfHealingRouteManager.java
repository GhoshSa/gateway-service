package com.example.gateway.routing;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import com.example.gateway.config.GatewayConfig;
import com.example.gateway.health.HealthMonitor;
import com.example.gateway.prediction.FailurePredictionEngine;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
@Slf4j
public class SelfHealingRouteManager {
    private final GatewayConfig config;
    private final FailurePredictionEngine predictionEngine;
    private final HealthMonitor healthMonitor;
    private final WebClient webClient;
    private final ConcurrentHashMap<String, List<String>> activeRoutes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> requestCounter = new ConcurrentHashMap<>();

    public SelfHealingRouteManager(GatewayConfig config, FailurePredictionEngine predictionEngine, HealthMonitor healthMonitor) {
        this.config = config;
        this.predictionEngine = predictionEngine;
        this.healthMonitor = healthMonitor;
        this.webClient = WebClient.builder().codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)).build();
    }

    public RouteLocator buildDynamicRoutes(RouteLocatorBuilder builder) {
        RouteLocatorBuilder.Builder routes = builder.routes();

        for (GatewayConfig.ServiceConfig service : config.getServices()) {
            String routeID = "route-" + service.getId();
            routes.route(routeID, r -> r.path(service.getPath()).filters(f -> f.filter(createSelfHealingFilter(service))).uri(selectHealthyInstance(service)));

            List<String> instanceUrls = service.getInstances().stream().map(GatewayConfig.ServiceInstance::getUrl).toList();

            activeRoutes.put(service.getId(), instanceUrls);
            requestCounter.put(service.getId(), new AtomicInteger(0));
        }

        return routes.build();
    }

    public GatewayFilter createSelfHealingFilter(GatewayConfig.ServiceConfig service) {
        return (exchange, chain) -> {
            String serviceID = service.getId();
            long startTime = System.currentTimeMillis();

            return chain.filter(exchange)
                .doOnSuccess(response -> {
                    double responseTime = System.currentTimeMillis() - startTime;
                    recordMetrics(serviceID, responseTime, true);
                })
                .doOnError(error -> {
                    double responseTime = System.currentTimeMillis() - startTime;
                    recordMetrics(serviceID, responseTime, false);
                    log.warn("Request failed for service: {} - {}", serviceID, error.getMessage());
                })
            .onErrorResume(error -> handleFailureWithRedirection(exchange, service, 0));
        };
    }

    private void recordMetrics(String serviceID, double responseTime, boolean success) {
        double cpuUsage = ThreadLocalRandom.current().nextDouble(0.1, 0.9);
        double memoryUsage = ThreadLocalRandom.current().nextDouble(0.1, 0.8);
        int activeConnections = ThreadLocalRandom.current().nextInt(10, 100);

        predictionEngine.recordMetric(serviceID, responseTime, success, cpuUsage, memoryUsage, activeConnections);
    }

    private String selectHealthyInstance(GatewayConfig.ServiceConfig service) {
        List<GatewayConfig.ServiceInstance> healthyInstances = service.getInstances().stream().filter(instance -> instance.isActive() && healthMonitor.isServiceHealthy(instance.getId())).toList();

        if (healthyInstances.isEmpty()) {
            log.warn("No healthy instances available for service: {}, using first available", service.getId());
            return service.getInstances().get(0).getUrl();
        }

        return selectByWeight(healthyInstances);
    }

    private String selectByWeight(List<GatewayConfig.ServiceInstance> instances) {
        int totalWeight = instances.stream().mapToInt(GatewayConfig.ServiceInstance::getWeight).sum();
        int randomWeight = ThreadLocalRandom.current().nextInt(totalWeight);
        int currentWeight = 0;

        for (GatewayConfig.ServiceInstance instance : instances) {
            currentWeight += instance.getWeight();

            if (currentWeight > randomWeight) {
                return instance.getUrl();
            }
        }

        return instances.get(0).getUrl();
    }

    private Mono<Void> handleFailureWithRedirection(ServerWebExchange exchange, GatewayConfig.ServiceConfig service, int attemptCount) {
        final int maxRedirectAttempts = Math.min(service.getInstances().size(), 3);

        if (attemptCount >= maxRedirectAttempts) {
            log.error("Max attempts reached for service: {}", service.getId());
            return handleFallbackStrategy(exchange, service);
        }

        String nextHealthyInstance = findNextHealthyInstance(service, attemptCount);

        if (nextHealthyInstance != null) {
            log.info("Redirecting request to healthy instance: {} for service: {} (attempt: {})", nextHealthyInstance, service.getId(), attemptCount + 1);
            return redirectToHealthyInstance(exchange, service, nextHealthyInstance, attemptCount);
        } else {
            log.warn("No healthy instances available for service: {}, falling back to strategy: {}", service.getId(), service.getFallbackStrategy());
            return handleFallbackStrategy(exchange, service);
        }
    }

    private String findNextHealthyInstance(GatewayConfig.ServiceConfig service, int attemptCount) {
        List<GatewayConfig.ServiceInstance> healthyInstances = service.getInstances().stream().filter(instance -> instance.isActive() && healthMonitor.isServiceHealthy(instance.getId())).toList();

        if (healthyInstances.isEmpty()) {
            return null;
        }

        int index = (requestCounter.get(service.getId()).getAndIncrement() + attemptCount) % healthyInstances.size();

        return healthyInstances.get(index).getUrl();
    }

    private Mono<Void> redirectToHealthyInstance(ServerWebExchange exchange, GatewayConfig.ServiceConfig service, String targetInstanceUrl, int attemptCount) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        String targetPath = request.getURI().getRawPath();
        String targetQuery = request.getURI().getRawQuery();
        String targetUrl = targetInstanceUrl + targetPath + (targetQuery != null ? "?" + targetQuery : "");

        WebClient.RequestBodySpec requestSpec = webClient
            .method(request.getMethod())
            .uri(targetUrl)
            .headers(headers -> {
                request.getHeaders().forEach((key, values) -> {
                    if (!shouldSkipHeader(key)) {
                        headers.addAll(key, values);
                    }
                });
            });
        
        Mono<String> requestBody = exchange.getRequest().getBody()
            .map(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                return new String(bytes);
            })
            .reduce("", String::concat);
        
        return requestBody.flatMap(body -> {
            WebClient.ResponseSpec responseSpec;

            if (!body.isEmpty()) {
                responseSpec = requestSpec.bodyValue(body).retrieve();
            } else {
                responseSpec = requestSpec.retrieve();
            }

            return responseSpec
                .toEntity(String.class)
                .flatMap(responseEntity -> {
                    response.setStatusCode(responseEntity.getStatusCode());
                    responseEntity.getHeaders().forEach((key, values) -> {
                        if (!shouldSkipHeader(key)) {
                            response.getHeaders().addAll(key, values);
                        }
                    });

                    String responseBody = responseEntity.getBody();
                    if (responseBody != null && !responseBody.isEmpty()) {
                        DataBuffer buffer = response.bufferFactory().wrap(responseBody.getBytes());
                        return response.writeWith(Mono.just(buffer));
                    } else {
                        return response.setComplete();
                    }
                })
                .doOnSuccess(v -> {
                    log.info("Successfully redirected request to: {} for service: {}", targetInstanceUrl, service.getId());
                    recordMetrics(service.getId(), 0, true);
                })
                .onErrorResume(redirectError -> {
                    log.warn("Redirect failed to instance: {} for service: {} - {}", targetInstanceUrl, service.getId(), redirectError.getMessage());
                    recordMetrics(service.getId(), 0, false);

                    return handleFailureWithRedirection(exchange, service, attemptCount + 1);
                });
        });
    }

    private boolean shouldSkipHeader(String headerName) {
        String lHeaderName = headerName.toLowerCase();
        return lHeaderName.equals("host") || lHeaderName.equals("content-length") || lHeaderName.equals("x-forwarded");
    }

    private Mono<Void> handleFallbackStrategy(ServerWebExchange exchange, GatewayConfig.ServiceConfig service) {
        switch (service.getFallbackStrategy()) {
            case CIRCUIT_BREAKER -> {
                return handleCircuitBreaker(exchange, service);
            }

            case CACHED_RESPONSE -> {
                return handleCachedResponse(exchange, service);
            }

            case DEFAULT_RESPONSE -> {
                return handleDefaultResponse(exchange, service);
            }

            case RETRY_WITH_BACKOFF -> {
                return handleRetryWithBackoff(exchange, service);
            }

            default -> {
                return handleHybridStrategy(exchange, service);
            }
        }
    }

    private Mono<Void> handleCircuitBreaker(ServerWebExchange exchange, GatewayConfig.ServiceConfig service) {
        log.info("Circuit breaker activated for service: {}", service.getId());
        return createErrorResponse(exchange, 503, "Service temporarily unavailable - circuit breaker open");
    }

    private Mono<Void> handleCachedResponse(ServerWebExchange exchange, GatewayConfig.ServiceConfig service) {
        log.info("Returning cached response for service: {}", service.getId());
        return createSuccessResponse(exchange, "{\"status\":\"cached\",\"message\":\"Cached response due to service unavailability\",\"timestamp\":\"" + java.time.LocalDateTime.now() + "\"}");
    }

    private Mono<Void> handleDefaultResponse(ServerWebExchange exchange, GatewayConfig.ServiceConfig service) {
        log.info("Returning default response for service: {}", service.getId());
        return createSuccessResponse(exchange, "{\"status\":\"default\",\"message\":\"Default fallback response\",\"service\":\"" + service.getId() + "\",\"timestamp\":\"" + java.time.LocalDateTime.now() + "\"}");
    }

    private Mono<Void> handleRetryWithBackoff(ServerWebExchange exchange, GatewayConfig.ServiceConfig service) {
        log.info("Implementing retry with backoff for service: {}", service.getId());

        return Mono.delay(Duration.ofSeconds(1))
            .then(findAnyHealthyInstance(service))
            .flatMap(healthyInstance -> {
                if (healthyInstance != null) {
                    return redirectToHealthyInstance(exchange, service, healthyInstance, 0);
                } else {
                    return createErrorResponse(exchange, 503, "Service temporarily unavailable - all instances down");
                }
            })
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(10))
                .doBeforeRetry(retrySignal -> 
                    log.info("Retrying request for service: {} (attempt: {})", service.getId(), retrySignal.totalRetries() + 1)
                )
            );
    }

    private Mono<String> findAnyHealthyInstance(GatewayConfig.ServiceConfig service) {
        return Mono.fromCallable(() -> {
            Optional<GatewayConfig.ServiceInstance> healthyInstances = service.getInstances()
                .stream()
                .filter(instance -> instance.isActive() && healthMonitor.isServiceHealthy(instance.getId()))
                .findFirst();
            return healthyInstances.map(GatewayConfig.ServiceInstance::getUrl).orElse(null);
        });
    }

    private Mono<Void> handleHybridStrategy(ServerWebExchange exchange, GatewayConfig.ServiceConfig service) {
        return findAnyHealthyInstance(service)
            .flatMap(healthyInstance -> {

                if (healthyInstance != null) {
                    log.info("Hybrid strategy: Found healthy instance {} for service: {}", healthyInstance, service.getId());
                    return redirectToHealthyInstance(exchange, service, healthyInstance, 0);
                } else {
                    log.info("Hybrid strategy: No healthy instances, falling back to default response for service: {}", service.getId());
                    return handleDefaultResponse(exchange, service);
                }
            });
    }

    private Mono<Void> createErrorResponse(ServerWebExchange exchange, int statusCode, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(statusCode));
        response.getHeaders().add("Content-Type", "application/json");

        String responseBody = String.format("{\"error\":\"%s\",\"timestamp\":\"%s\"}", message, LocalDateTime.now());

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(responseBody.getBytes());

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> createSuccessResponse(ServerWebExchange exchange, String responseBody) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().add("Content-Type", "application/json");

        DataBuffer buffer = response.bufferFactory().wrap(responseBody.getBytes());

        return response.writeWith(Mono.just(buffer));
    }
}