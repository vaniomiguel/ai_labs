package com.example.ollama.outsmart;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds only Outsmart settings, so this feature cannot change the existing chat client. */
@ConfigurationProperties(prefix = "outsmart")
public record OutsmartProperties(int maxGames, Ollama ollama) {

    /** Connection settings reserved for the local model adapter added in a later step. */
    public record Ollama(URI baseUrl, Duration requestTimeout) {
    }
}
