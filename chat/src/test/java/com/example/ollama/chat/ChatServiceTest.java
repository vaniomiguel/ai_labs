package com.example.ollama.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ollama.client.TextResponseFormat;
import org.junit.jupiter.api.Test;

class ChatServiceTest {

    @Test
    void delegatesPromptToConfiguredClient() {
        ChatService service = new ChatService((systemPrompt, userPrompt, responseFormat) -> {
            assertEquals("", systemPrompt);
            assertEquals(TextResponseFormat.DEFAULT, responseFormat);
            return "Reply to: " + userPrompt;
        });

        assertEquals("Reply to: hello", service.generateResponse("hello").text());
    }

    @Test
    void delegatesSystemAndUserPromptsToConfiguredClient() {
        ChatService service = new ChatService((systemPrompt, userPrompt, responseFormat) ->
                systemPrompt + ": " + userPrompt);

        assertEquals("Be concise: hello", service.generateResponse("Be concise", "hello").text());
    }

    @Test
    void delegatesSelectedFormatWithEitherPromptForm() {
        ChatService service = new ChatService((systemPrompt, userPrompt, responseFormat) ->
                systemPrompt + ": " + userPrompt + " (" + responseFormat + ")");

        assertEquals(": hello (JSON_OBJECT)",
                service.generateResponse("hello", TextResponseFormat.JSON_OBJECT).text());
        assertEquals("Be concise: hello (JSON_OBJECT)",
                service.generateResponse("Be concise", "hello", TextResponseFormat.JSON_OBJECT).text());
    }
}
