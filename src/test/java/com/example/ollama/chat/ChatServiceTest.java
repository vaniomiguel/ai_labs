package com.example.ollama.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatServiceTest {

    @Test
    void delegatesPromptToConfiguredClient() {
        ChatService service = new ChatService((systemPrompt, userPrompt) -> {
            assertEquals("", systemPrompt);
            return "Reply to: " + userPrompt;
        });

        assertEquals("Reply to: hello", service.generateResponse("hello").text());
    }

    @Test
    void delegatesSystemAndUserPromptsToConfiguredClient() {
        ChatService service = new ChatService((systemPrompt, userPrompt) ->
                systemPrompt + ": " + userPrompt);

        assertEquals("Be concise: hello", service.generateResponse("Be concise", "hello").text());
    }
}
