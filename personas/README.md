# Persona conversations

A standalone Java 25 / Spring Boot application for conversations between local Ollama personas.

## Run

From the repository root, with Ollama running and the desired models installed:

```sh
./mvnw -pl personas -am package
java -jar personas/target/personas-0.0.1-SNAPSHOT.jar
```

`PORT` overrides the default port of 8083. `OLLAMA_BASE_URL` overrides the default
OpenAI-compatible API address, `http://localhost:11434/v1`. Model IDs are chosen
for each persona in the interface. Shared Ollama settings come from `../shared/ollama-client`.

## Behavior and API


Open [http://localhost:8083/personas](http://localhost:8083/personas) to start a conversation between two Ollama personas. Choose an installed model for each speaker, edit their names and default personalities, and enter a topic. The page shows new replies as they arrive. **Stop** immediately freezes the displayed transcript, even if an Ollama request is still finishing. **Restart with these settings** clears the transcript and starts again with the current form values. Conversations live in server memory and disappear when the app restarts; the page remembers the current conversation ID for the browser tab.

The JSON API provides `GET /api/personas/models`, `POST /api/personas/conversations`, `GET /api/personas/conversations/{id}`, `POST /api/personas/conversations/{id}/stop`, `POST /api/personas/conversations/{id}/restart`, and `DELETE /api/personas/conversations/{id}`. Create and restart accept a topic and two personas, each with `name`, `model`, and `personality`. The service keeps at most 100 conversations; delete old ones through the API to free space.

`PersonaConversationReplyService` owns the shared persona instructions and builds a role-based message history. Each speaker sees its own earlier replies as assistant messages and the other speaker's replies as user messages. `OllamaPersonaClient` only maps those messages to Ollama's chat API; another model adapter can reuse the same conversation logic through `ModelChatClient`.

