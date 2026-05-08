package com.enterprise.dataanalyst.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AppConfig {

    /**
     * Async executor for LLM inference.
     * Single thread — model is not thread-safe.
     */
    @Bean(name = "llmExecutor")
    public Executor llmExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        exec.setQueueCapacity(10);
        exec.setThreadNamePrefix("llm-");
        exec.initialize();
        return exec;
    }

    /**
     * Async executor for file parsing.
     */
    @Bean(name = "parserExecutor")
    public Executor parserExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(20);
        exec.setThreadNamePrefix("parser-");
        exec.initialize();
        return exec;
    }
}
