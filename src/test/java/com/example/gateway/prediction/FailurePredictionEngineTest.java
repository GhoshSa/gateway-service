package com.example.gateway.prediction;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FailurePredictionEngineTest {
    private FailurePredictionEngine failurePredictionEngine;

    private ConcurrentHashMap<String, FailurePredictionEngine.ServiceMetrics> metricMap;
    private ConcurrentHashMap<String, FailurePredictionEngine.PredictionModel> modelMap;

    @BeforeEach
    void setUp() {
        metricMap = new ConcurrentHashMap<>();
        modelMap = new ConcurrentHashMap<>();
        failurePredictionEngine = new FailurePredictionEngine(metricMap, modelMap);
    }

    @Test
    void testRecordMetric_createServiceMetricsAndAddPoint() {
        String serviceId = "test-service-1";
        failurePredictionEngine.recordMetric(serviceId, 150.0, true, 0.4, 0.6, 50);

        assertTrue(metricMap.containsKey(serviceId), "ServiceMetrics should be created for this serviceId");
        FailurePredictionEngine.ServiceMetrics metrics = metricMap.get(serviceId);
        assertNotNull(metrics, "ServiceMetrics object should not be null");
        assertEquals(1, metrics.getMetricPoints().size(), "MetricPoint should be added to ServiceMetrics");
    }
}
