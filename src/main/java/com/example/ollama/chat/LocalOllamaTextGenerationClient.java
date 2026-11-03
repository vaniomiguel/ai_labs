package com.example.ollama.chat;

import com.example.ollama.config.LocalOllamaProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import java.util.Objects;

/** Generates text with a local Ollama server through the OpenAI SDK. */
public final class LocalOllamaTextGenerationClient implements TextGenerationClient {

    private final OpenAIClient client;
    private final String model;

    public LocalOllamaTextGenerationClient(LocalOllamaProperties properties) {
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(properties.baseUrl())
                .apiKey("ollama")
                .build();
        this.model = properties.model();
    }

    @Override
    public String generateText(String systemPrompt, String userPrompt, TextResponseFormat responseFormat) {
        Objects.requireNonNull(responseFormat, "responseFormat");
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model));
        if (responseFormat == TextResponseFormat.JSON_OBJECT) {
            builder.responseFormat(ResponseFormatJsonObject.builder().build());
            String jsonInstruction = "Respond with a valid JSON object.";
            builder.addSystemMessage(systemPrompt.isBlank()
                    ? jsonInstruction
                    : systemPrompt + "\n" + jsonInstruction);
        } else if (!systemPrompt.isBlank()) {
            builder.addSystemMessage(systemPrompt);
        }
        ChatCompletionCreateParams params = builder.addUserMessage(userPrompt).build();
        return client.chat().completions().create(params)
                .choices().get(0).message().content().orElse("");
    }
}
