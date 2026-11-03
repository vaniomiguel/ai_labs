package com.example.ollama.personas;

import java.util.List;
import java.util.UUID;

public record PersonaConversationResponse(UUID id, String topic,
        PersonaConversationRequest.Persona first, PersonaConversationRequest.Persona second,
        String status, String error, List<Message> messages) {

    public record Message(int number, String speaker, String text) {
    }
}
