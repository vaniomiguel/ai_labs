package com.example.ollama.personas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PersonaConversationService {

    private final OllamaPersonaClient ollama;
    private final PersonaReplyGenerator generator;
    private final Executor executor;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    @Autowired
    PersonaConversationService(OllamaPersonaClient ollama) {
        this(ollama, ollama, Executors.newVirtualThreadPerTaskExecutor());
    }

    PersonaConversationService(OllamaPersonaClient ollama, PersonaReplyGenerator generator, Executor executor) {
        this.ollama = ollama;
        this.generator = generator;
        this.executor = executor;
    }

    public List<String> models() {
        return ollama.models();
    }

    public synchronized PersonaConversationResponse create(PersonaConversationRequest request) {
        validate(request);
        if (sessions.size() >= 100) {
            throw new IllegalStateException("Conversation limit reached. Delete an old conversation first.");
        }
        Session session = new Session(UUID.randomUUID(), request);
        sessions.put(session.id, session);
        start(session);
        return snapshot(session);
    }

    public PersonaConversationResponse get(UUID id) {
        return snapshot(find(id));
    }

    public PersonaConversationResponse stop(UUID id) {
        Session session = find(id);
        synchronized (session) {
            if (session.status.equals("running")) {
                session.runId++;
                session.status = "stopped";
            }
            return snapshot(session);
        }
    }

    public PersonaConversationResponse restart(UUID id, PersonaConversationRequest request) {
        validate(request);
        Session session = find(id);
        synchronized (session) {
            session.runId++;
            session.request = request;
            session.messages.clear();
            session.error = null;
            start(session);
            return snapshot(session);
        }
    }

    public void delete(UUID id) {
        Session session = find(id);
        synchronized (session) {
            session.runId++;
            session.status = "stopped";
            sessions.remove(id, session);
        }
    }

    private void validate(PersonaConversationRequest request) {
        if (request.first().name().trim().equalsIgnoreCase(request.second().name().trim())) {
            throw new IllegalArgumentException("The two personas need different names");
        }
        List<String> installed = models();
        if (!installed.contains(request.first().model()) || !installed.contains(request.second().model())) {
            throw new IllegalArgumentException("Select installed Ollama models for both personas");
        }
    }

    private Session find(UUID id) {
        Session session = sessions.get(id);
        if (session == null) {
            throw new NoSuchElementException("Conversation not found: " + id);
        }
        return session;
    }

    private PersonaConversationResponse snapshot(Session session) {
        synchronized (session) {
            return new PersonaConversationResponse(session.id, session.request.topic(),
                    session.request.first(), session.request.second(), session.status, session.error,
                    List.copyOf(session.messages));
        }
    }

    private void start(Session session) {
        long runId = ++session.runId;
        session.status = "running";
        try {
            executor.execute(() -> run(session, runId));
        } catch (RuntimeException exception) {
            session.status = "failed";
            session.error = "Could not start the conversation";
        }
    }

    private void run(Session session, long runId) {
        while (true) {
            PersonaConversationRequest request;
            List<PersonaConversationResponse.Message> history;
            PersonaConversationRequest.Persona speaker;
            synchronized (session) {
                if (session.runId != runId || !session.status.equals("running")) {
                    return;
                }
                request = session.request;
                history = List.copyOf(session.messages);
                speaker = history.size() % 2 == 0 ? request.first() : request.second();
            }
            try {
                String reply = generator.reply(speaker, request.topic(), history);
                synchronized (session) {
                    if (session.runId != runId || !session.status.equals("running")) {
                        return;
                    }
                    session.messages.add(new PersonaConversationResponse.Message(
                            session.messages.size() + 1, speaker.name(), reply));
                }
            } catch (RuntimeException exception) {
                synchronized (session) {
                    if (session.runId == runId && session.status.equals("running")) {
                        session.status = "failed";
                        session.error = "Model request failed: " +
                                (exception.getMessage() == null ? "unknown error" : exception.getMessage());
                    }
                }
                return;
            }
        }
    }

    @PreDestroy
    public void close() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    private static final class Session {
        private final UUID id;
        private PersonaConversationRequest request;
        private final List<PersonaConversationResponse.Message> messages = new ArrayList<>();
        private String status = "stopped";
        private String error;
        private long runId;

        private Session(UUID id, PersonaConversationRequest request) {
            this.id = id;
            this.request = request;
        }
    }
}
