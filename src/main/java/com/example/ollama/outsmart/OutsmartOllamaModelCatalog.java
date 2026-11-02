package com.example.ollama.outsmart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads locally installed Ollama model names for Outsmart's player selectors. */
@Component
public class OutsmartOllamaModelCatalog {

    /** Model suggestions and an optional display message when Ollama cannot be reached. */
    public record Catalog(List<String> models, String error) {
    }

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper;
    private final URI tagsUrl;
    private final Duration timeout;

    /** Uses only Outsmart's Ollama address, independent of the separate chat service. */
    public OutsmartOllamaModelCatalog(OutsmartProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.tagsUrl = URI.create(properties.ollama().baseUrl().toString().replaceAll("/+$", "") + "/api/tags");
        this.timeout = Duration.ofSeconds(3);
    }

    /** Fetches model names from Ollama; setup remains usable if the local server is offline. */
    public Catalog list() {
        HttpRequest request = HttpRequest.newBuilder(tagsUrl).timeout(timeout).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Catalog(List.of(), "Ollama is unavailable (HTTP " + response.statusCode() + ").");
            }
            JsonNode models = mapper.readTree(response.body()).path("models");
            if (!models.isArray()) {
                return new Catalog(List.of(), "Ollama returned an unexpected model list.");
            }
            List<String> names = models.valueStream().map(model -> model.path("name"))
                    .filter(JsonNode::isString).map(JsonNode::asString).distinct().sorted().toList();
            return new Catalog(names, names.isEmpty() ? "No local models found. Pull one with Ollama first." : null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Catalog(List.of(), "Could not load Ollama models because the request was interrupted.");
        } catch (IOException | RuntimeException exception) {
            return new Catalog(List.of(), "Could not load Ollama models. Check that Ollama is running.");
        }
    }
}
