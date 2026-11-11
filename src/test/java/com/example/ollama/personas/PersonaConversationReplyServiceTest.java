package com.example.ollama.personas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PersonaConversationReplyServiceTest {

    private final CapturingClient client = new CapturingClient();
    private final PersonaConversationReplyService replies = new PersonaConversationReplyService(client);
    private final PersonaConversationRequest.Persona explorer =
            new PersonaConversationRequest.Persona("Explorer", "model-a", "Curious");
    private final List<PersonaConversationResponse.Message> history = List.of(
            new PersonaConversationResponse.Message(1, "Explorer", "What if we tried it?"),
            new PersonaConversationResponse.Message(2, "Skeptic", "What evidence do we have?"));

    @Test
    void sendsOwnTurnsAsAssistantAndOtherPersonaAsUser() {
        assertThat(replies.reply(explorer, "An idea", history)).isEqualTo("model reply");
        assertThat(client.model).isEqualTo("model-a");
        assertThat(client.messages).containsExactly(
                new ChatMessage(ChatMessage.Role.SYSTEM,
                        "You are Explorer.\nPersonality: Curious\nStay in character. "
                                + "Continue the discussion naturally in 1-3 sentences. Speak only for yourself; "
                                + "do not add speaker labels or write the other person's reply."),
                new ChatMessage(ChatMessage.Role.USER, "Conversation topic: An idea"),
                new ChatMessage(ChatMessage.Role.ASSISTANT, "What if we tried it?"),
                new ChatMessage(ChatMessage.Role.USER, "Skeptic: What evidence do we have?"));
    }

    @Test
    void firstReplyStartsWithTopicAndNoArtificialAssistantHistory() {
        replies.reply(explorer, "An idea", List.of());
        assertThat(client.messages).extracting(ChatMessage::role)
                .containsExactly(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER);
    }

    @Test
    void secondPersonaSeesItsOwnTurnsAsAssistant() {
        var skeptic = new PersonaConversationRequest.Persona("Skeptic", "model-b", "Careful");
        replies.reply(skeptic, "An idea", history);
        assertThat(client.messages).extracting(ChatMessage::role)
                .containsExactly(ChatMessage.Role.SYSTEM, ChatMessage.Role.USER,
                        ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT);
        assertThat(client.messages.getLast().text()).isEqualTo("What evidence do we have?");
    }

    private static final class CapturingClient implements ModelChatClient {
        private String model;
        private List<ChatMessage> messages;

        @Override
        public List<String> models() {
            return List.of("model-a");
        }

        @Override
        public String complete(String model, List<ChatMessage> messages) {
            this.model = model;
            this.messages = messages;
            return "model reply";
        }
    }
}
