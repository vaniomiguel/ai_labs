package com.example.ollama.personas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class PersonaConversationServiceTest {

    private static final PersonaConversationRequest REQUEST = new PersonaConversationRequest("A topic",
            new PersonaConversationRequest.Persona("Explorer", "model-a", "Curious"),
            new PersonaConversationRequest.Persona("Skeptic", "model-b", "Careful"));

    @Test
    void stoppingDuringModelCallDiscardsItsReplyAndRestartStartsFresh() {
        ModelChatClient client = mock(ModelChatClient.class);
        when(client.models()).thenReturn(List.of("model-a", "model-b"));
        AtomicReference<PersonaConversationService> serviceRef = new AtomicReference<>();
        AtomicReference<java.util.UUID> idRef = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        PersonaReplyGenerator generator = (speaker, topic, history) -> {
            int call = calls.incrementAndGet();
            serviceRef.get().stop(idRef.get());
            return "Reply " + call;
        };
        AtomicReference<Runnable> queued = new AtomicReference<>();
        PersonaConversationService service = new PersonaConversationService(client, generator, queued::set);
        serviceRef.set(service);

        var created = service.create(REQUEST);
        idRef.set(created.id());
        queued.get().run();
        assertThat(service.get(created.id()).status()).isEqualTo("stopped");
        assertThat(service.get(created.id()).messages()).isEmpty();

        var restarted = service.restart(created.id(), REQUEST);
        assertThat(restarted.status()).isEqualTo("running");
        assertThat(restarted.messages()).isEmpty();
        queued.get().run();
        assertThat(service.get(created.id()).messages()).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void previousRunCannotAddMessagesAfterRestart() {
        ModelChatClient client = mock(ModelChatClient.class);
        when(client.models()).thenReturn(List.of("model-a", "model-b"));
        AtomicReference<Runnable> firstRun = new AtomicReference<>();
        AtomicReference<Runnable> secondRun = new AtomicReference<>();
        AtomicInteger scheduled = new AtomicInteger();
        PersonaConversationService service = new PersonaConversationService(client,
                (speaker, topic, history) -> "Hello", task -> {
                    if (scheduled.getAndIncrement() == 0) firstRun.set(task);
                    else secondRun.set(task);
                });
        var created = service.create(REQUEST);
        service.restart(created.id(), REQUEST);
        firstRun.get().run();
        assertThat(service.get(created.id()).messages()).isEmpty();
        assertThat(secondRun.get()).isNotNull();
    }
}
