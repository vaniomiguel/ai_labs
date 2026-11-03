package com.example.ollama.personas;

import java.util.List;

interface PersonaReplyGenerator {
    String reply(PersonaConversationRequest.Persona speaker, String topic,
            List<PersonaConversationResponse.Message> history);
}
