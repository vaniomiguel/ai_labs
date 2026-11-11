package com.example.ollama.personas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.example.ollama.client.LocalOllamaProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class OllamaPersonaClient implements ModelChatClient {

    private final OpenAIClient client;
    private final HttpClient http;
    private final URI tagsUrl;
    private final ObjectMapper mapper;

    OllamaPersonaClient(LocalOllamaProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        client = OpenAIOkHttpClient.builder()
                .baseUrl(properties.baseUrl())
                .apiKey("ollama")
                .timeout(Duration.ofMinutes(2))
                .maxRetries(0)
                .build();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        tagsUrl = URI.create(properties.baseUrl().replaceAll("/v1/?$", "").replaceAll("/+$", "") + "/api/tags");
    }

    @Override
    public List<String> models() {
        try {
            HttpRequest request = HttpRequest.newBuilder(tagsUrl).timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new PersonaOllamaUnavailableException("Ollama model list returned HTTP " + response.statusCode());
            }
            List<String> names = new ArrayList<>();
            for (JsonNode model : mapper.readTree(response.body()).path("models")) {
                JsonNode capabilities = model.path("capabilities");
                boolean canChat = !capabilities.isArray();
                for (JsonNode capability : capabilities) {
                    if ("completion".equals(capability.asString())) canChat = true;
                }
                if (canChat && model.path("name").isString()) names.add(model.path("name").asString());
            }
            return names.stream().distinct().sorted().toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PersonaOllamaUnavailableException("Interrupted while listing Ollama models", exception);
        } catch (java.io.IOException exception) {
            throw new PersonaOllamaUnavailableException("Could not reach Ollama", exception);
        }
    }

    @Override
    public String complete(String model, List<ChatMessage> messages) {
        var builder = ChatCompletionCreateParams.builder().model(model);
        for (ChatMessage message : messages) {
            switch (message.role()) {
                case SYSTEM -> builder.addSystemMessage(message.text());
                case USER -> builder.addUserMessage(message.text());
                case ASSISTANT -> builder.addAssistantMessage(message.text());
            }
        }
        var params = builder.build();
        var choices = client.chat().completions().create(params).choices();
        if (choices.isEmpty()) {
            throw new IllegalStateException("Ollama returned no response");
        }
        String content = choices.getFirst().message().content().orElse("").trim();
        if (content.isEmpty()) {
            throw new IllegalStateException("Ollama returned an empty response");
        }
        return content;
    }
}
