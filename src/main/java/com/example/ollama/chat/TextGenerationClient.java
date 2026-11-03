package com.example.ollama.chat;

/** Generates text with the configured language model, independent of its provider. */
public interface TextGenerationClient {

    /** Generates text from a user prompt without a system prompt. */
    default String generateText(String userPrompt) {
        return generateText("", userPrompt);
    }

    /** Generates text using system instructions and a user prompt. */
    String generateText(String systemPrompt, String userPrompt);
}
