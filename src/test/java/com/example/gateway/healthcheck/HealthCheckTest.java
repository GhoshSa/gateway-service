package com.example.gateway.healthcheck;

import com.example.gateway.health.HealthMonitor;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class HealthCheckTest {
    private MockWebServer mockWebServer;
    private HealthMonitor healthMonitor;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        WebClient.Builder webClientBuilder = WebClient.builder().baseUrl(mockWebServer.url("/").toString());
        healthMonitor = new HealthMonitor(webClientBuilder);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void shouldRecordSuccessOn200OkResponse() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        StepVerifier.create(healthMonitor.checkHealth("test-service-1", "/service-url", "/health-endpoint")).assertNext(health -> {
            assertThat(health.getServiceID()).isEqualTo("test-service-1");
            assertThat(health.isHealthy()).isTrue();
            assertThat(health.getSuccessCount().get()).isEqualTo(1L);
            assertThat(health.getFailureCount().get()).isEqualTo(0L);
            assertThat(health.getConsecutiveFailure().get()).isEqualTo(0L);
        }).verifyComplete();

        HealthMonitor.ServiceHealth storedHealth = healthMonitor.getServiceHealth("test-service-1");
        assertThat(storedHealth).isNotNull();
        assertThat(storedHealth.isHealthy()).isTrue();
        assertThat(storedHealth.getFailureCount().get()).isEqualTo(0L);

        RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/service-url/health-endpoint");
    }

    @Test
    void shouldRecordFailureOnNon2xxResponse() throws InterruptedException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(healthMonitor.checkHealth("test-service-2", "/service-url", "/health-endpoint")).assertNext(health -> {
            assertThat(health.getServiceID()).isEqualTo("test-service-2");
            assertThat(health.isHealthy()).isTrue();
            assertThat(health.getSuccessCount().get()).isEqualTo(0L);
            assertThat(health.getFailureCount().get()).isEqualTo(1L);
            assertThat(health.getConsecutiveFailure().get()).isEqualTo(1L);
        }).verifyComplete();

        HealthMonitor.ServiceHealth storedHealth = healthMonitor.getServiceHealth("test-service-2");
        assertThat(storedHealth).isNotNull();
        assertThat(storedHealth.isHealthy()).isTrue();
        assertThat(storedHealth.getFailureCount().get()).isEqualTo(1L);

        RecordedRequest request = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/service-url/health-endpoint");
    }

    @Test
    void shouldMarkServiceUnhealthyAfterThreeConsecutiveFailure() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));
        mockWebServer.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(healthMonitor.checkHealth("test-service-3", "/service-url", "/health-endpoint")).expectNextMatches(health -> health.isHealthy() && health.getConsecutiveFailure().get() == 1L).verifyComplete();
        assertThat(healthMonitor.isServiceHealthy("test-service-3")).isTrue();

        StepVerifier.create(healthMonitor.checkHealth("test-service-3", "/service-url", "/health-endpoint")).expectNextMatches(health -> health.isHealthy() && health.getConsecutiveFailure().get() == 2L).verifyComplete();
        assertThat(healthMonitor.isServiceHealthy("test-service-3")).isTrue();

        StepVerifier.create(healthMonitor.checkHealth("test-service-3", "/service-url", "/health-endpoint")).assertNext(health -> {
            assertThat(health.getServiceID()).isEqualTo("test-service-3");
            assertThat(health.isHealthy()).isFalse();
            assertThat(health.getFailureCount().get()).isEqualTo(3L);
            assertThat(health.getConsecutiveFailure().get()).isEqualTo(3L);
        }).verifyComplete();

        HealthMonitor.ServiceHealth storedHealth = healthMonitor.getServiceHealth("test-service-3");
        assertThat(storedHealth).isNotNull();
        assertThat(storedHealth.isHealthy()).isFalse();
        assertThat(storedHealth.getFailureCount().get()).isEqualTo(3L);
    }
}
