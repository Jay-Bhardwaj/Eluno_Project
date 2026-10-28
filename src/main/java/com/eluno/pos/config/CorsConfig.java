package com.eluno.pos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Same-origin deployment needs no CORS; this enables one explicit frontend origin when split later. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
  private final String allowedOrigin;

  public CorsConfig(@Value("${eluno.allowed-origin:}") String allowedOrigin) {
    this.allowedOrigin = allowedOrigin;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    if (!allowedOrigin.isBlank()) {
      registry.addMapping("/api/**")
          .allowedOrigins(allowedOrigin)
          .allowedMethods("GET", "POST", "OPTIONS")
          .allowedHeaders("Content-Type");
    }
  }
}
