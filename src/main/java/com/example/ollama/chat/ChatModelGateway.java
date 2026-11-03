package com.example.ollama.chat;

/** Sends a prompt to the configured model provider. */
public interface ChatModelGateway {

    String complete(String prompt);
}
