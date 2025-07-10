package com.example.gateway.prediction;

import java.time.LocalDateTime;
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

        assertFalse(metricMap.containsKey(serviceId));
        assertFalse(modelMap.containsKey(serviceId));

        FailurePredictionEngine.ServiceMetrics metrics = new FailurePredictionEngine.ServiceMetrics(serviceId);
        metricMap.put(serviceId, metrics);

        for (int i = 0; i < 10; i++) {
            metrics.addMetricPoint(new FailurePredictionEngine.MetricPoint(LocalDateTime.now().minusMinutes(10 - i), 100.0 + i, true, 0.4, 0.6, 50 + i));
        }

        failurePredictionEngine.recordMetric(serviceId, 150.0, true, 0.4, 0.6, 50);

        assertTrue(metricMap.containsKey(serviceId), "ServiceMetrics should be created for this serviceId");
        FailurePredictionEngine.ServiceMetrics updatedMetrics = metricMap.get(serviceId);
        assertNotNull(updatedMetrics, "ServiceMetrics object should not be null");
        assertEquals(11, updatedMetrics.getMetricPoints().size(), "MetricPoint should be added to ServiceMetrics");

        assertTrue(modelMap.containsKey(serviceId));
        FailurePredictionEngine.PredictionModel predictionModel = modelMap.get(serviceId);
        assertNotNull(predictionModel, "PredictionModel object should not be null");
        assertEquals(1, predictionModel.getTrainingCount(), "Prints training count");
    }
}
