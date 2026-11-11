package com.example.ollama.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared Ollama settings; the existing chat.local-ollama property prefix is retained. */
@ConfigurationProperties(prefix = "chat.local-ollama")
public record LocalOllamaProperties(String baseUrl, String model) {
}
