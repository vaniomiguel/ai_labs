package com.example.ollama.chat;

import org.springframework.stereotype.Service;

/** Sends chat prompts through the configured model provider. */
@Service
public class ChatService {

    private final TextGenerationClient textGenerationClient;

    public ChatService(TextGenerationClient textGenerationClient) {
        this.textGenerationClient = textGenerationClient;
    }

    public ChatResponse generateResponse(String userPrompt) {
        return new ChatResponse(textGenerationClient.generateText(userPrompt));
    }

    public ChatResponse generateResponse(String systemPrompt, String userPrompt) {
        return new ChatResponse(textGenerationClient.generateText(systemPrompt, userPrompt));
    }

    public ChatResponse generateResponse(String userPrompt, TextResponseFormat responseFormat) {
        return new ChatResponse(textGenerationClient.generateText(userPrompt, responseFormat));
    }

    public ChatResponse generateResponse(String systemPrompt, String userPrompt,
            TextResponseFormat responseFormat) {
        return new ChatResponse(textGenerationClient.generateText(systemPrompt, userPrompt, responseFormat));
    }
}
