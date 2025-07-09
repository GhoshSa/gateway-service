package com.example.gateway.prediction;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class FailurePredictionEngine {
    private final @Qualifier("metricsMap") ConcurrentHashMap<String, ServiceMetrics> metricsMap;
    private final @Qualifier("modelMap") ConcurrentHashMap<String, PredictionModel> modelMap;

    public void recordMetric(String serviceId, double responseTime, boolean success, double cpuUsage, double memoryUsage, int activeConnections) {
        ServiceMetrics metrics = metricsMap.computeIfAbsent(serviceId, k -> new ServiceMetrics(k));
        MetricPoint point = new MetricPoint(LocalDateTime.now(), responseTime, success, cpuUsage, memoryUsage, activeConnections);
        metrics.addMetricPoint(point);
        updatePredictionModel(serviceId, metrics);
    }

    private void updatePredictionModel(String serviceId, ServiceMetrics metrics) {
        PredictionModel model = modelMap.computeIfAbsent(serviceId, k -> new PredictionModel(k));
        model.train(metrics);
    }

    public PredictionResult predictFailure(String serviceId, int minutesAhead) {
        PredictionModel model = modelMap.get(serviceId);
        if (model == null) {
            return new PredictionResult(serviceId, 0.0, "No prediction model available", false);
        }
        return model.predict(minutesAhead);
    }

    @Data
    public static class ServiceMetrics {
        private final String serviceId;
        private final Queue<MetricPoint> metricPoints;
        private static final int MAX_POINTS = 1000;

        public ServiceMetrics(String serviceId) {
            this.serviceId = serviceId;
            this.metricPoints = new LinkedList<>();
        }

        public synchronized void addMetricPoint(MetricPoint point) {
            metricPoints.offer(point);
            while (metricPoints.size() > MAX_POINTS) {
                metricPoints.poll();
            }
        }

        public List<MetricPoint> getRecentPoints(int minutes) {
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
            return metricPoints.stream().filter(point -> point.getTimeStamp().isAfter(cutoff)).toList();
        }
    }

    @Data
    public static class MetricPoint {
        private final LocalDateTime timeStamp;
        private final double responseTime;
        private final boolean success;
        private final double cpuUsage;
        private final double memoryUsage;
        private final int activeConnections;
    }

    @Data
    public static class PredictionModel {
        private  final String serviceId;
        private double[] weights;
        private double bias;
        private int trainingCount;

        public PredictionModel(String serviceId) {
            this.serviceId = serviceId;
            this.weights = new double[]{0.3, 0.2, 0.2, 0.2, 0.1};
            this.bias = 0.0;
            this.trainingCount = 0;
        }

        public void train(ServiceMetrics metrics) {
            List<MetricPoint> recentPoints = metrics.getRecentPoints(10);

            if (recentPoints.size() < 10) {
                return;
            }

            double learningRate = 0.01;
            for (MetricPoint point : recentPoints) {
                double[] features = extractFeatures(point);
                double prediction = predict(features);
                double actual = point.isSuccess() ? 0.0 : 1.0;
                double error = actual - prediction;

                for (int i = 0; i < weights.length && i < features.length; i++) {
                    weights[i] += learningRate * error * features[i];
                }
                bias += learningRate * error;
            }

            trainingCount++;
            if (trainingCount % 100 == 0) {
                log.info("Updated prediction model for service: {} (training count: {})", serviceId, trainingCount);
            }
        }

        private double[] extractFeatures(MetricPoint point) {
            return new double[]{
                point.getResponseTime() / 1000.0,
                point.getCpuUsage(),
                point.getMemoryUsage(),
                point.getActiveConnections() / 100.0,
                point.isSuccess() ? 0.0 : 1.0
            };
        }

        private double predict(double[] features) {
            double sum = bias;
            for (int i = 0; i < weights.length && i < features.length; i++) {
                sum += weights[i] * features[i];
            }
            return 1.0 / (1.0 + Math.exp(-sum));
        }

        public PredictionResult predict(int minutesAhead) {
            double riskScore = Math.max(0.0, Math.min(1.0, weights[0] * 0.5 + weights[1] * 0.3 + bias));
            String reason = riskScore > 0.7 ? "High failure probability based on recent metrics" : "Service appears stable";
            return new PredictionResult(reason, riskScore, reason, riskScore > 0.7);
        }
    }

    @Data
    public static class PredictionResult {
        private final String serviceId;
        private final double riskScore;
        private final String reason;
        private final boolean actionRequired;
        private final LocalDateTime timestamp = LocalDateTime.now();
    }
}
