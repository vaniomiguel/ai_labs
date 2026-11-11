package com.example.ollama.personas;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

/** Builds each persona's view of the conversation, independent of the model provider. */
@Service
class PersonaConversationReplyService implements PersonaReplyGenerator {

    private final ModelChatClient client;

    PersonaConversationReplyService(ModelChatClient client) {
        this.client = client;
    }

    @Override
    public String reply(PersonaConversationRequest.Persona speaker, String topic,
            List<PersonaConversationResponse.Message> history) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessage.Role.SYSTEM,
                "You are " + speaker.name() + ".\nPersonality: " + speaker.personality()
                        + "\nStay in character. Continue the discussion naturally in 1-3 sentences. "
                        + "Speak only for yourself; do not add speaker labels or write the other person's reply."));
        messages.add(new ChatMessage(ChatMessage.Role.USER, "Conversation topic: " + topic));

        // Preserve recent context without sending an unbounded transcript to the model.
        for (var turn : history.subList(Math.max(0, history.size() - 20), history.size())) {
            boolean ownTurn = turn.speaker().equals(speaker.name());
            messages.add(new ChatMessage(ownTurn ? ChatMessage.Role.ASSISTANT : ChatMessage.Role.USER,
                    ownTurn ? turn.text() : turn.speaker() + ": " + turn.text()));
        }
        return client.complete(speaker.model(), List.copyOf(messages));
    }
}
