package com.example.ollama.outsmart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Calls Ollama's local chat API and translates its JSON reply into an Outsmart decision. */
@Component
public class OutsmartOllamaMoveGenerator implements OutsmartMoveGenerator {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final OutsmartPromptFactory prompts;
    private final URI chatUrl;
    private final java.time.Duration timeout;

    /** Uses Outsmart-specific Ollama settings, leaving the existing chat client untouched. */
    @Autowired
    public OutsmartOllamaMoveGenerator(OutsmartProperties properties, ObjectMapper mapper,
            OutsmartPromptFactory prompts) {
        this(HttpClient.newHttpClient(), mapper, prompts, properties);
    }

    /** Allows tests to supply a local HTTP client while retaining the production configuration. */
    OutsmartOllamaMoveGenerator(HttpClient httpClient, ObjectMapper mapper,
            OutsmartPromptFactory prompts, OutsmartProperties properties) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        OutsmartProperties.Ollama ollama = Objects.requireNonNull(properties.ollama(), "outsmart.ollama");
        URI baseUrl = Objects.requireNonNull(ollama.baseUrl(), "outsmart.ollama.base-url");
        if (!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) {
            throw new IllegalArgumentException("outsmart.ollama.base-url must use HTTP or HTTPS");
        }
        chatUrl = URI.create(baseUrl.toString().replaceAll("/+$", "") + "/api/chat");
        timeout = Objects.requireNonNull(ollama.requestTimeout(), "outsmart.ollama.request-timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("outsmart.ollama.request-timeout must be positive");
        }
    }

    /** Sends a move request; an unavailable server aborts the turn, while malformed output forfeits it. */
    @Override
    public OutsmartDecision decide(OutsmartGame game, OutsmartGame.PlayerState player) {
        if (!"ollama".equalsIgnoreCase(player.model().provider())) {
            throw new IllegalArgumentException("Outsmart Ollama client requires an ollama model provider");
        }
        OutsmartPromptFactory.Prompt prompt = prompts.forPlayer(game, player);
        // Model IDs come from the game lineup, not a shared default, so players can use different local models.
        Map<String, Object> payload = Map.of(
                "model", player.model().id(),
                "stream", false,
                "format", moveSchema(game, player),
                "messages", java.util.List.of(
                        Map.of("role", "system", "content", prompt.system()),
                        Map.of("role", "user", "content", prompt.user())));
        try {
            HttpRequest request = HttpRequest.newBuilder(chatUrl)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OutsmartModelUnavailableException("Ollama returned HTTP " + response.statusCode());
            }
            JsonNode content = mapper.readTree(response.body()).path("message").path("content");
            if (!content.isString()) {
                return new OutsmartDecision.Invalid("Ollama response has no message content");
            }
            return parseMove(content.asString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutsmartModelUnavailableException("Ollama request interrupted");
        } catch (IOException e) {
            throw new OutsmartModelUnavailableException("Could not reach Ollama at " + chatUrl);
        } catch (OutsmartModelUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            return new OutsmartDecision.Invalid("Ollama response was not valid JSON");
        }
    }

    /** Constrains Ollama output to the exact move fields and this player's opponent names. */
    private Map<String, Object> moveSchema(OutsmartGame game, OutsmartGame.PlayerState player) {
        List<String> opponents = game.players().stream().map(OutsmartGame.PlayerState::name)
                .filter(name -> !name.equals(player.name())).toList();
        Map<String, Object> messages = new LinkedHashMap<>();
        for (String opponent : opponents) {
            messages.put(opponent, Map.of("type", "string"));
        }
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "secret_strategy", Map.of("type", "string"),
                        "give_coin_to", Map.of("type", "string", "enum", opponents),
                        "take_coin_from", Map.of("type", "string", "enum", opponents),
                        "private_messages", Map.of("type", "object", "properties", messages,
                                "required", opponents, "additionalProperties", false)),
                "required", List.of("secret_strategy", "give_coin_to", "take_coin_from", "private_messages"),
                "additionalProperties", false);
    }

    /** Reads the required JSON fields; the rule engine still checks legal targets and messages. */
    private OutsmartDecision parseMove(String content) {
        try {
            JsonNode move = mapper.readTree(content);
            if (!move.isObject() || !move.path("secret_strategy").isString()
                    || !move.path("give_coin_to").isString()
                    || !move.path("take_coin_from").isString()
                    || !move.path("private_messages").isObject()) {
                return new OutsmartDecision.Invalid("Ollama move is missing required JSON fields");
            }
            Map<String, String> messages = new LinkedHashMap<>();
            var fields = move.path("private_messages").properties();
            for (var entry : fields) {
                if (!entry.getValue().isString()) {
                    return new OutsmartDecision.Invalid("Ollama private messages must be text");
                }
                messages.put(entry.getKey(), entry.getValue().asString());
            }
            return new OutsmartDecision.Valid(new OutsmartMove(
                    move.path("secret_strategy").asString(), move.path("give_coin_to").asString(),
                    move.path("take_coin_from").asString(), messages));
        } catch (RuntimeException e) {
            return new OutsmartDecision.Invalid("Ollama move is not valid JSON");
        }
    }
}
