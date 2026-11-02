package com.example.ollama.chat;

import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Sends the existing chat endpoint's prompts to its configured Ollama model. */
@Service
public class ChatService {

    private final OpenAIClient openAIClient;
    private final String model;

    /** Reads the chat model once when the service is created. */
    public ChatService(
            OpenAIClient openAIClient,
            @Value("${openai.model}") String model) {
        this.openAIClient = openAIClient;
        this.model = model;
    }

    /** Makes one chat completion request and returns the first response's text. */
    public ChatResponse ask(String prompt) {
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(ChatModel.of(model))
                .build();
        String text = openAIClient.chat().completions().create(params)
                .choices().get(0).message().content().orElse("");
        return new ChatResponse(text);
    }
}
