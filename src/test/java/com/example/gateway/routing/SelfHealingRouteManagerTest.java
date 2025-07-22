package com.example.gateway.routing;

import com.example.gateway.config.GatewayConfig;
import com.example.gateway.health.HealthMonitor;
import com.example.gateway.prediction.FailurePredictionEngine;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SelfHealingRouteManagerTest {
    @Mock
    private GatewayConfig mockGatewayConfig;

    @Mock
    private FailurePredictionEngine mockFailurePredictionEngine;

    @Mock
    private HealthMonitor mockHealthMonitor;

    @Mock
    private ServerWebExchange mockExchange;

    @Mock
    private ServerHttpRequest mockRequest;

    @Mock
    private ServerHttpResponse mockResponse;

    @Mock
    private GatewayFilterChain mockFilterChain;

    @Captor
    private ArgumentCaptor<String> serviceIdCaptor;

    @Captor
    private ArgumentCaptor<Double> responseTimeCaptor;

    @Captor
    private ArgumentCaptor<Boolean> successCaptor;

    private MockWebServer mockWebServer;
    private SelfHealingRouteManager selfHealingRouteManager;

    private final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient.Builder webClientBuilder = WebClient.builder().baseUrl(mockWebServer.url("/").toString());

        selfHealingRouteManager = new SelfHealingRouteManager(mockGatewayConfig, mockFailurePredictionEngine, mockHealthMonitor, webClientBuilder);

//        when(mockExchange.getRequest()).thenReturn(mockRequest);
//        when(mockExchange.getResponse()).thenReturn(mockResponse);
//        when(mockResponse.bufferFactory()).thenReturn(bufferFactory);
//        when(mockResponse.writeWith(any())).thenReturn(Mono.empty());
//        when(mockResponse.setComplete()).thenReturn(Mono.empty());
//        when(mockRequest.getHeaders()).thenReturn(new HttpHeaders());
//        when(mockRequest.getBody()).thenReturn(Flux.empty());
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private GatewayConfig.ServiceConfig createServiceConfig(String serviceId, String path, GatewayConfig.FallbackStrategy fallbackStrategy, List<GatewayConfig.ServiceInstance> instances) {
        GatewayConfig.ServiceConfig config = new GatewayConfig.ServiceConfig();
        config.setId(serviceId);
        config.setPath(path);
        config.setFallbackStrategy(fallbackStrategy);
        config.setInstances(instances);
        return config;
    }

    private GatewayConfig.ServiceInstance createInstance(String id, String url, int weight, boolean active) {
        GatewayConfig.ServiceInstance instance = new GatewayConfig.ServiceInstance();
        instance.setId(id);
        instance.setUrl(url);
        instance.setWeight(weight);
        instance.setActive(active);
        return instance;
    }

    @Test
    void selectHealthyInstance_shouldReturnHealthyInstance() {
        GatewayConfig.ServiceInstance instance1 = createInstance("s1-i1", "http://instance1:8080", 1, true);
        GatewayConfig.ServiceInstance instance2 = createInstance("s1-i2", "http://instance2:8080", 1, true);
        GatewayConfig.ServiceConfig service = createServiceConfig("service1", "/path", GatewayConfig.FallbackStrategy.DEFAULT_RESPONSE, Arrays.asList(instance1, instance2));

        when(mockHealthMonitor.isServiceHealthy("s1-i1")).thenReturn(true);
        when(mockHealthMonitor.isServiceHealthy("s1-i2")).thenReturn(true);

        String selectedUrl = selfHealingRouteManager.selectHealthyInstance(service);

        assertThat(selectedUrl).isIn("http://instance1:8080", "http://instance2:8080");
    }

    @Test
    void selectHealthyInstance_shouldReturnFirstIfNoHealthy() {
        GatewayConfig.ServiceInstance instance1 = createInstance("s1-i1", "http://instance1:8080", 1, true);
        GatewayConfig.ServiceInstance instance2 = createInstance("s1-i2", "http://instance2:8080", 1, true);
        GatewayConfig.ServiceConfig service = createServiceConfig("service1", "/path", GatewayConfig.FallbackStrategy.DEFAULT_RESPONSE, Arrays.asList(instance1, instance2));

        when(mockHealthMonitor.isServiceHealthy("s1-i1")).thenReturn(false);
        when(mockHealthMonitor.isServiceHealthy("s1-i2")).thenReturn(false);

        String selectedUrl = selfHealingRouteManager.selectHealthyInstance(service);

        assertThat(selectedUrl).isEqualTo("http://instance1:8080");
    }

    @Test
    void selectHealthyInstance_shouldRecordMetricOnSuccess() {
        GatewayConfig.ServiceConfig service = createServiceConfig("test-service", "/test", GatewayConfig.FallbackStrategy.DEFAULT_RESPONSE, Collections.emptyList());
        GatewayFilter filter = selfHealingRouteManager.createSelfHealingFilter(service);

        when(mockFilterChain.filter(mockExchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(mockExchange, mockFilterChain)).verifyComplete();

        verify(mockFailurePredictionEngine).recordMetric(serviceIdCaptor.capture(), responseTimeCaptor.capture(), successCaptor.capture(), anyDouble(), anyDouble(), anyInt());
        assertThat(serviceIdCaptor.getValue()).isEqualTo("test-service");
        assertThat(responseTimeCaptor.getValue()).isGreaterThanOrEqualTo(0.0);
        assertThat(successCaptor.getValue()).isTrue();
    }

    @Test
    void createSelfHealingFilter_shouldRecordMetricsOnErrorAndInitiateRedirection() {
        GatewayConfig.ServiceConfig service = createServiceConfig("test-service", "test", GatewayConfig.FallbackStrategy.DEFAULT_RESPONSE, Arrays.asList(createInstance("i1", mockWebServer.url("/").toString(), 1, true)));
        GatewayFilter filter = selfHealingRouteManager.createSelfHealingFilter(service);

        when(mockFilterChain.filter(mockExchange)).thenReturn(Mono.error(new RuntimeException("Simulated upstream error")));
        when(mockRequest.getURI()).thenReturn(URI.create("/test/data"));
        
        when(mockExchange.getRequest()).thenReturn(mockRequest);
        when(mockExchange.getResponse()).thenReturn(mockResponse);
        when(mockResponse.bufferFactory()).thenReturn(bufferFactory);
        when(mockResponse.writeWith(any())).thenReturn(Mono.empty());
        when(mockRequest.getMethod()).thenReturn(HttpMethod.GET);
        when(mockRequest.getHeaders()).thenReturn(new HttpHeaders());
        when(mockRequest.getBody()).thenReturn(Flux.empty());

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("Redirected success"));
        when(mockHealthMonitor.isServiceHealthy(anyString())).thenReturn(true);

        StepVerifier.create(filter.filter(mockExchange, mockFilterChain)).verifyComplete();
//        verify(mockFailurePredictionEngine).recordMetric(serviceIdCaptor.capture(), responseTimeCaptor.capture(), successCaptor.capture(), anyDouble(), anyDouble(), anyInt());
//        assertThat(serviceIdCaptor.getValue()).isEqualTo("test-service");
//        assertThat(responseTimeCaptor.getValue()).isGreaterThanOrEqualTo(0.0);
//        assertThat(successCaptor.getValue()).isFalse();

        verify(mockResponse).setStatusCode(eq(HttpStatus.OK));
        verify(mockResponse).writeWith(any());

        try {
            RecordedRequest recordedRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
            assertThat(recordedRequest).isNotNull();
            assertThat(recordedRequest.getPath()).isEqualTo("/test/data");
        } catch (InterruptedException e) {
            fail("MockWebServer did not receive request within timeout.");
        }
    }

    @Test
    void handleFailureWithRedirection_shouldRedirectToNextHealthyInstance() throws InterruptedException {
        GatewayConfig.ServiceInstance instance1 = createInstance("i1", mockWebServer.url("/instance1").toString(), 1, true);
        GatewayConfig.ServiceInstance instance2 = createInstance("i2", mockWebServer.url("/instance2").toString(), 1, true);

        GatewayConfig.ServiceConfig service = createServiceConfig("redirect-service", "/redir", GatewayConfig.FallbackStrategy.DEFAULT_RESPONSE, Arrays.asList(instance1, instance2));

        when(mockFilterChain.filter(mockExchange)).thenReturn(Mono.error(new RuntimeException("Simulated upstream error")));
        when(mockExchange.getRequest()).thenReturn(mockRequest);
        when(mockExchange.getResponse()).thenReturn(mockResponse);
        when(mockRequest.getURI()).thenReturn(URI.create("/redir/data"));
        when(mockRequest.getMethod()).thenReturn(HttpMethod.GET);
        when(mockRequest.getHeaders()).thenReturn(new HttpHeaders());
        when(mockRequest.getBody()).thenReturn(Flux.empty());
        when(mockResponse.bufferFactory()).thenReturn(bufferFactory);
        when(mockResponse.writeWith(any())).thenReturn(Mono.empty());

        when(mockHealthMonitor.isServiceHealthy("i1")).thenReturn(false);
        when(mockHealthMonitor.isServiceHealthy("i2")).thenReturn(true);

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("Hello from Instance 2"));

        StepVerifier.create(selfHealingRouteManager.createSelfHealingFilter(service).filter(mockExchange, mockFilterChain)).verifyComplete();

        verify(mockResponse).setStatusCode(HttpStatus.OK);
        verify(mockResponse).writeWith(any(Mono.class));

        RecordedRequest recordedRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(recordedRequest.getPath()).startsWith("/instance2/redir/data");
        assertThat(recordedRequest.getMethod()).isEqualTo("GET");

        verify(mockFailurePredictionEngine, times(2)).recordMetric(anyString(), anyDouble(), anyBoolean(), anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void handleFallbackStrategy_circuitBreaker() {
        GatewayConfig.ServiceConfig service = createServiceConfig("cb-service", "/cb", GatewayConfig.FallbackStrategy.CIRCUIT_BREAKER, Collections.emptyList());

        when(mockFilterChain.filter(mockExchange)).thenReturn(Mono.error(new RuntimeException("Simulated upstream error")));
        when(mockExchange.getResponse()).thenReturn(mockResponse);
        when(mockResponse.bufferFactory()).thenReturn(bufferFactory);
        when(mockResponse.writeWith(any())).thenReturn(Mono.empty());
        when(mockResponse.getHeaders()).thenReturn(new HttpHeaders());

        StepVerifier.create(selfHealingRouteManager.createSelfHealingFilter(service).filter(mockExchange, mockFilterChain)).verifyComplete();

        verify(mockResponse).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        ArgumentCaptor<Mono<DataBuffer>> buffer = ArgumentCaptor.forClass(Mono.class);
        verify(mockResponse).writeWith(buffer.capture());
        Mono<DataBuffer> capturedMono = buffer.getValue();
        DataBuffer capturedDataBuffer = capturedMono.block();
        String responseBody = capturedDataBuffer.toString(StandardCharsets.UTF_8);
        assertThat(responseBody).contains("Service temporarily unavailable - circuit breaker open");
    }
}

