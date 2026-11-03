package com.example.ollama.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatServiceTest {

    @Test
    void delegatesPromptToConfiguredGateway() {
        ChatService service = new ChatService(prompt -> "Reply to: " + prompt);

        assertEquals("Reply to: hello", service.ask("hello").text());
    }
}
