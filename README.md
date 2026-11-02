# AI Labs

AI Labs is a growing collection of projects for exploring artificial intelligence. It brings together experiments, prototypes, and small applications built to test concepts, try ideas, and learn by doing.

This repository is a work in progress. Projects and documentation will be added and changed over time as new ideas are explored.

## Projects

### OpenAI-compatible Spring Boot chat

A Java 25 / Spring Boot REST API that uses the official OpenAI Java SDK. It is configured to use a local Ollama model by default, and can also connect to OpenAI or another compatible API by changing configuration.

#### Requirements

- Java 25 JDK
- Maven 3.6.3 or newer
- [Ollama](https://ollama.com/) installed and running locally, with a model downloaded (default: `llama3.2`)
- Or an OpenAI API key when using the OpenAI API

#### Run

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

Choose another installed Ollama model with `OPENAI_MODEL`; set `OPENAI_BASE_URL` if needed. The API key defaults to the placeholder `ollama` because Ollama does not require one:

```bash
OPENAI_MODEL=qwen3.5:4b OPENAI_BASE_URL=http://localhost:11434/v1 OPENAI_API_KEY=ollama mvn spring-boot:run
```

To use OpenAI instead, set `OPENAI_BASE_URL=https://api.openai.com/v1`, provide your real `OPENAI_API_KEY`, and choose a model your account can access with `OPENAI_MODEL`.
