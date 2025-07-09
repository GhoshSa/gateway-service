package com.example.gateway.config;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.gateway.prediction.FailurePredictionEngine;

@Configuration
public class AppConfig {
    @Bean
    public ConcurrentHashMap<String, FailurePredictionEngine.ServiceMetrics> metricsMap() {
        return new ConcurrentHashMap<>();
    }

    @Bean
    public ConcurrentHashMap<String, FailurePredictionEngine.PredictionModel> modelMap() {
        return new ConcurrentHashMap<>();
    }
}
