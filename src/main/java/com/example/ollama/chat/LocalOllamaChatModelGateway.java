package com.example.ollama.chat;

import com.example.ollama.config.LocalOllamaProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

/** Uses a locally running Ollama server through its OpenAI-compatible API. */
public final class LocalOllamaChatModelGateway implements ChatModelGateway {

    private final OpenAIClient client;
    private final String model;

    public LocalOllamaChatModelGateway(LocalOllamaProperties properties) {
        this.client = OpenAIOkHttpClient.builder()
                .baseUrl(properties.baseUrl())
                .apiKey("ollama")
                .build();
        this.model = properties.model();
    }

    @Override
    public String complete(String prompt) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(ChatModel.of(model))
                .build();
        return client.chat().completions().create(params)
                .choices().get(0).message().content().orElse("");
    }
}
