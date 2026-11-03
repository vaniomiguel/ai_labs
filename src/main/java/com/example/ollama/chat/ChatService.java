package com.example.ollama.chat;

import org.springframework.stereotype.Service;

/** Sends chat prompts through the configured model provider. */
@Service
public class ChatService {

    private final ChatModelGateway modelGateway;

    public ChatService(ChatModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    public ChatResponse ask(String prompt) {
        return new ChatResponse(modelGateway.complete(prompt));
    }
}
