package com.example.ollama.outsmart;

import java.time.Duration;
import java.util.List;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.stereotype.Component;

/** Reads local Ollama model names through the OpenAI SDK for player selectors. */
@Component
public class OutsmartOllamaModelCatalog {

    /** Model suggestions and an optional display message when Ollama cannot be reached. */
    public record Catalog(List<String> models, String error) {
    }

    private final OpenAIClient client;

    /** Uses only Outsmart's Ollama address, independent of the separate chat service. */
    public OutsmartOllamaModelCatalog(OutsmartProperties properties) {
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(properties.ollama().baseUrl().toString().replaceAll("/+$", "") + "/v1")
                .apiKey("ollama")
                .timeout(Duration.ofSeconds(3))
                .maxRetries(0)
                .build();
    }

    /** Fetches model names from Ollama; setup remains usable if the local server is offline. */
    public Catalog list() {
        try {
            List<String> names = client.models().list().data().stream()
                    .map(model -> model.id()).distinct().sorted().toList();
            return new Catalog(names, names.isEmpty() ? "No local models found. Pull one with Ollama first." : null);
        } catch (OpenAIServiceException exception) {
            return new Catalog(List.of(), "Ollama is unavailable (HTTP " + exception.statusCode() + ").");
        } catch (OpenAIException exception) {
            return new Catalog(List.of(), "Could not load Ollama models. Check that Ollama is running.");
        }
    }
}
