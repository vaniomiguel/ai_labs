package com.example.ollama.personas;

import java.util.List;

/** A model provider's transport for an already prepared conversation. */
interface ModelChatClient {
    List<String> models();

    String complete(String model, List<ChatMessage> messages);
}
