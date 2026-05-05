package com.enterprise.dataanalyst.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC Configuration.
 *
 * WHY THIS IS NEEDED:
 *
 * 1. CORS:
 *    Browser security blocks requests between different origins.
 *    Our UI (localhost:8080) calls our API (localhost:8080) —
 *    same origin, but explicitly configuring CORS prevents
 *    any future issues if port changes.
 *
 * 2. Static Resources:
 *    index.html is served from classpath:/static/.
 *    Spring Boot auto-configures this but we make it explicit
 *    for clarity and future extensibility.
 *
 * 3. Future:
 *    If we add file download endpoints or export features,
 *    resource handlers will be needed here.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * CORS Configuration.
     *
     * WHY LOCALHOST ONLY:
     * We only allow localhost origins — this means:
     * - No external website can call our API
     * - No cross-origin data leakage possible
     * - Enterprise data stays within local browser session
     *
     * This is an extra security layer on top of offline operation.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "http://localhost:8080",
                        "http://127.0.0.1:8080"
                )
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    /**
     * Static Resource Handler.
     *
     * WHY:
     * Serves index.html and any future static assets
     * (CSS, JS files if we ever externalize them)
     * from src/main/resources/static/ folder.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/");
    }
}