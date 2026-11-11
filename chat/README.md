# Local Ollama chat

A Java 25 / Spring Boot REST API that uses a local Ollama model. `ChatService` depends on `TextGenerationClient`; `LocalOllamaTextGenerationClient` is the current implementation. The shared interface is in `../shared/ollama-client` and supports a user prompt alone or a system prompt plus a user prompt. Both forms accept an optional `TextResponseFormat`: `DEFAULT` leaves the provider's response format unchanged, while `JSON_OBJECT` requests JSON object output. A future server or cloud provider can supply another implementation without changing the chat endpoint or service.

## Requirements

- Java 25 JDK
- The included Maven wrapper (Maven is downloaded on first use)
- [Ollama](https://ollama.com/) installed and running locally, with a model downloaded (default: `gemma3:1b`)

## Run

From the repository root, build this project and its shared library, then download the model and start the app:

```bash
./mvnw -pl chat -am package
ollama pull gemma3:1b
java -jar chat/target/chat-0.0.1-SNAPSHOT.jar
```

Ollama uses its OpenAI-compatible API at `http://localhost:11434/v1`. The Spring Boot API listens on `http://localhost:8080`. Send a prompt:

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Explain dependency injection in one sentence."}'
```

Example response:

```json
{"text":"Dependency injection is a way to provide an object with the collaborators it needs from the outside."}
```

Choose another installed Ollama model with `OLLAMA_MODEL`; set `OLLAMA_BASE_URL` if your local Ollama server uses another address:

```bash
OLLAMA_MODEL=qwen3.5:4b OLLAMA_BASE_URL=http://localhost:11434/v1 java -jar chat/target/chat-0.0.1-SNAPSHOT.jar
```

The local adapter uses Ollama's OpenAI-compatible API. Its placeholder API key is internal; Ollama does not require credentials. The Outsmart game has a separate move-generator interface and its own Ollama settings.

