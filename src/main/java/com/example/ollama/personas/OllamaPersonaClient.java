package com.example.ollama.personas;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.ArrayList;

import com.example.ollama.config.LocalOllamaProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class OllamaPersonaClient implements PersonaReplyGenerator {

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

    List<String> models() {
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
    public String reply(PersonaConversationRequest.Persona speaker, String topic,
            List<PersonaConversationResponse.Message> history) {
        StringBuilder prompt = new StringBuilder("Topic: ").append(topic).append("\n\nConversation so far:\n");
        // Recent turns are enough for context and keep long conversations within model limits.
        history.stream().skip(Math.max(0, history.size() - 20)).forEach(message ->
                prompt.append(message.speaker()).append(": ").append(message.text()).append("\n\n"));
        prompt.append("Reply as ").append(speaker.name()).append(". Continue the discussion naturally in 1-3 sentences. ")
                .append("Do not write the other person's response.");
        var params = ChatCompletionCreateParams.builder()
                .model(speaker.model())
                .addSystemMessage("You are " + speaker.name() + ". Your personality: " + speaker.personality()
                        + " Stay in character. Speak only for yourself and do not add speaker labels.")
                .addUserMessage(prompt.toString())
                .build();
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
