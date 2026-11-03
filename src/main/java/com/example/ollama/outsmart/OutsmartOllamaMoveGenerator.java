package com.example.ollama.outsmart;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Calls local Ollama through the OpenAI SDK and translates its reply into a decision. */
@Component
public class OutsmartOllamaMoveGenerator implements OutsmartMoveGenerator {

    private final OpenAIClient client;
    private final ObjectMapper mapper;
    private final OutsmartPromptFactory prompts;

    /** Uses Outsmart-specific Ollama settings, leaving the existing chat client untouched. */
    @Autowired
    public OutsmartOllamaMoveGenerator(OutsmartProperties properties, ObjectMapper mapper,
            OutsmartPromptFactory prompts) {
        this(localClient(properties), mapper, prompts);
    }

    OutsmartOllamaMoveGenerator(OpenAIClient client, ObjectMapper mapper, OutsmartPromptFactory prompts) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    private static OpenAIClient localClient(OutsmartProperties properties) {
        OutsmartProperties.Ollama ollama = Objects.requireNonNull(properties.ollama(), "outsmart.ollama");
        URI baseUrl = Objects.requireNonNull(ollama.baseUrl(), "outsmart.ollama.base-url");
        if (!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) {
            throw new IllegalArgumentException("outsmart.ollama.base-url must use HTTP or HTTPS");
        }
        Duration timeout = Objects.requireNonNull(ollama.requestTimeout(), "outsmart.ollama.request-timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("outsmart.ollama.request-timeout must be positive");
        }
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl.toString().replaceAll("/+$", "") + "/v1")
                .apiKey("ollama")
                .timeout(timeout)
                .maxRetries(0)
                .build();
    }

    /** Sends a move request; an unavailable server aborts the turn, while malformed output forfeits it. */
    @Override
    public OutsmartDecision decide(OutsmartGame game, OutsmartGame.PlayerState player) {
        if (!"ollama".equalsIgnoreCase(player.model().provider())) {
            throw new IllegalArgumentException("Outsmart Ollama client requires an ollama model provider");
        }
        OutsmartPromptFactory.Prompt prompt = prompts.forPlayer(game, player);
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(player.model().id())
                .addSystemMessage(prompt.system())
                .addUserMessage(prompt.user())
                .responseFormat(ResponseFormatJsonSchema.builder()
                        .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                                .name("outsmart_move")
                                .strict(true)
                                .schema(JsonValue.from(moveSchema(game, player))
                                        .convert(ResponseFormatJsonSchema.JsonSchema.Schema.class))
                                .build())
                        .build())
                .build();
        try {
            var choices = client.chat().completions().create(params).choices();
            if (choices.isEmpty() || choices.getFirst().message().content().isEmpty()) {
                return new OutsmartDecision.Invalid("Ollama response has no message content");
            }
            return parseMove(choices.getFirst().message().content().orElseThrow());
        } catch (OpenAIServiceException e) {
            throw new OutsmartModelUnavailableException("Ollama returned HTTP " + e.statusCode());
        } catch (OpenAIIoException e) {
            throw new OutsmartModelUnavailableException("Could not reach Ollama");
        } catch (OpenAIInvalidDataException e) {
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
