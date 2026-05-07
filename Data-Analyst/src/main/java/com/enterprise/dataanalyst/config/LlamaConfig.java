package com.enterprise.dataanalyst.config;

import de.kherud.llama.LlamaModel;
import de.kherud.llama.ModelParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * Loads SQLCoder-7B GGUF model via llama.cpp Java binding.
 *
 * WHY llama.cpp JAVA BINDING:
 * llama.cpp is the most stable GGUF inference engine.
 * de.kherud:llama wraps llama.cpp as a Java library.
 * Native binaries are bundled inside the JAR — no separate install.
 * GGUF format directly supported — no conversion needed.
 *
 * OFFLINE: Zero network calls. Pure local inference.
 */
@Configuration
@Slf4j
public class LlamaConfig {

    @Value("${app.llm.model-path}")
    private String modelPath;

    @Value("${app.llm.gpu-layers:0}")
    private int gpuLayers;

    @Bean(destroyMethod = "close")
    public LlamaModel llamaModel() {
        File modelFile = new File(modelPath);

        if (!modelFile.exists()) {
            throw new IllegalStateException(
                    "Model not found: " + modelFile.getAbsolutePath());
        }

        int cores = Runtime.getRuntime().availableProcessors();
        log.info("Cores: {}, GPU Layers: {}, Model: {} MB",
                cores, gpuLayers, modelFile.length() / (1024 * 1024));

        try {
            // 4.x style — builder pattern
            ModelParameters params = new ModelParameters()
                            .setModel(modelFile.getAbsolutePath())
                            .setCtxSize(contextLength)
                            .setThreads(cores)
                            .setGpuLayers(gpuLayers);

            return new LlamaModel(params);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Model load failed: " + e.getMessage(), e);
        }
    }
}

