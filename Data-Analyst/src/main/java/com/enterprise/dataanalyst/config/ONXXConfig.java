package com.enterprise.dataanalyst.config;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads MiniLM ONNX model and tokenizer at startup.
 *
 * WHY COPY TO TEMP FILE:
 * OrtSession requires a file path — cannot read from JAR stream directly.
 * We copy bundled model to temp file once at startup (~100ms).
 * Temp file is deleted on JVM exit.
 */
@Configuration
@Slf4j
public class ONXXConfig {

    @Value("${app.llm.onnx-model-path}")
    private String onnxModelPath;

    @Value("${app.llm.tokenizer-path}")
    private String tokenizerPath;

    @Bean
    public OrtEnvironment ortEnvironment() {
        return OrtEnvironment.getEnvironment();
    }

    @Bean(destroyMethod = "close")
    public OrtSession ortSession(OrtEnvironment env) throws Exception {
        log.info("Loading MiniLM ONNX model...");

        Path tempModel = copyToTemp(onnxModelPath, "minilm", ".onnx");

        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2); // lightweight — intent classification only

        OrtSession session = env.createSession(tempModel.toString(), opts);
        log.info("MiniLM ONNX model loaded. Ready for intent classification.");
        return session;
    }

    @Bean
    public HuggingFaceTokenizer huggingFaceTokenizer() throws Exception {
        log.info("Loading HuggingFace tokenizer...");
        Path tempTokenizer = copyToTemp(tokenizerPath, "tokenizer", ".json");
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tempTokenizer);
        log.info("Tokenizer loaded.");
        return tokenizer;
    }

    private Path copyToTemp(String classpathResource, String prefix, String suffix)
            throws IOException {
        Path temp = Files.createTempFile(prefix, suffix);
        temp.toFile().deleteOnExit();
        try (InputStream is = new ClassPathResource(classpathResource).getInputStream()) {
            Files.copy(is, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }
}