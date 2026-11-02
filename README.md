# OpenAI-compatible Spring Boot starter

A small Java 17 / Spring Boot application that exposes a REST endpoint and uses the official OpenAI Java SDK. It defaults to a local Ollama server, and can be pointed at any compatible API by changing configuration.

## Requirements

- Java 17 or newer
- Maven 3.6.3 or newer
- [Ollama](https://ollama.com/) installed and running locally, with a model downloaded (default: `llama3.2`)
- Or an OpenAI API key when using the OpenAI API

## Run

For the default local Ollama setup, download the model and start the app:

```bash
ollama pull llama3.2
mvn spring-boot:run
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

For Ollama, choose another installed model with `OPENAI_MODEL` and set `OPENAI_BASE_URL` if needed. The API key defaults to the placeholder `ollama` because Ollama does not require one:

```bash
OPENAI_MODEL=qwen3.5:4b OPENAI_BASE_URL=http://localhost:11434/v1 OPENAI_API_KEY=ollama mvn spring-boot:run
```

To use OpenAI instead, set `OPENAI_BASE_URL=https://api.openai.com/v1`, provide your real `OPENAI_API_KEY`, and choose a model your account can access with `OPENAI_MODEL`.
