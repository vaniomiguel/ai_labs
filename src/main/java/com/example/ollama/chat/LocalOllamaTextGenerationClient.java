package com.example.ollama.chat;

import com.example.ollama.config.LocalOllamaProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

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
    public String generateText(String systemPrompt, String userPrompt) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model));
        if (!systemPrompt.isBlank()) {
            builder.addSystemMessage(systemPrompt);
        }
        ChatCompletionCreateParams params = builder.addUserMessage(userPrompt).build();
        return client.chat().completions().create(params)
                .choices().get(0).message().content().orElse("");
    }
}
