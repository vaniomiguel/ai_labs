package com.example.ollama.client;

/** Generates text with the configured language model, independent of its provider. */
public interface TextGenerationClient {

    /** Generates text from a user prompt without a system prompt. */
    default String generateText(String userPrompt) {
        return generateText("", userPrompt, TextResponseFormat.DEFAULT);
    }

    /** Generates text using system instructions and a user prompt. */
    default String generateText(String systemPrompt, String userPrompt) {
        return generateText(systemPrompt, userPrompt, TextResponseFormat.DEFAULT);
    }

    /** Generates text from a user prompt in the requested response format. */
    default String generateText(String userPrompt, TextResponseFormat responseFormat) {
        return generateText("", userPrompt, responseFormat);
    }

    /** Generates text using both prompts in the requested response format. */
    String generateText(String systemPrompt, String userPrompt, TextResponseFormat responseFormat);
}
