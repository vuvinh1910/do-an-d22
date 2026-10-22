package com.platform.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.worker.runner.WebClientHttpRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkerRunnerConfig {

    @Bean
    public WebClientHttpRunner webClientHttpRunner(ObjectMapper objectMapper) {
        return new WebClientHttpRunner(objectMapper);
    }
}
