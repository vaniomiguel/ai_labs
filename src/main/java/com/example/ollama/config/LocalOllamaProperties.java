package com.example.ollama.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the local Ollama chat adapter. */
@ConfigurationProperties(prefix = "chat.local-ollama")
public record LocalOllamaProperties(String baseUrl, String model) {
}
