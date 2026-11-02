# AI Labs

AI Labs is a growing collection of projects for exploring artificial intelligence. It brings together experiments, prototypes, and small applications built to test concepts, try ideas, and learn by doing.

This repository is a work in progress. Projects and documentation will be added and changed over time as new ideas are explored.

## Projects

### OpenAI-compatible Spring Boot chat

A Java 25 / Spring Boot REST API that uses the official OpenAI Java SDK. It is configured to use a local Ollama model by default, and can also connect to OpenAI or another compatible API by changing configuration.

#### Requirements

- Java 25 JDK
- The included Maven wrapper (Maven is downloaded on first use)
- [Ollama](https://ollama.com/) installed and running locally, with a model downloaded (default: `gemma3:1b`)
- Or an OpenAI API key when using the OpenAI API

#### Run

For the default local Ollama setup, download the model and start the app:

```bash
ollama pull gemma3:1b
./mvnw spring-boot:run
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
OPENAI_MODEL=qwen3.5:4b OPENAI_BASE_URL=http://localhost:11434/v1 OPENAI_API_KEY=ollama ./mvnw spring-boot:run
```

To use OpenAI instead, set `OPENAI_BASE_URL=https://api.openai.com/v1`, provide your real `OPENAI_API_KEY`, and choose a model your account can access with `OPENAI_MODEL`.

### Outsmart game (in progress)

The Outsmart feature has its own `com.example.ollama.outsmart` package and `outsmart.*` configuration. Its Ollama calls use `OUTSMART_OLLAMA_BASE_URL` (default `http://localhost:11434`) and `OUTSMART_OLLAMA_REQUEST_TIMEOUT` (default `2m`). These settings do not affect `/api/chat`. Game endpoints and the UI will be added in later steps.

The `mise.toml` file selects Temurin 25 for this project. Link the Homebrew installation with `mise link java@temurin-25 /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`, then run `./mvnw` from a shell with mise activated.

The game rules and in-memory game registry are implemented. Games start with 12 coins per player and end after 10 turns or a bankruptcy. Invalid moves cost one coin; a qualifying alliance transfers two extra coins from its target. The registry keeps up to `OUTSMART_MAX_GAMES` games (default `100`) in memory and supports removing them; games disappear when the application stops.

Step 3 adds player-specific prompts and an Ollama move generator. Each player uses the Ollama model ID supplied in the game's participant list. The generator calls Ollama's local `/api/chat` endpoint with JSON mode, parses the move, and returns an invalid decision when the model response or request fails. The rule engine then checks target names and required messages. Each prompt includes public balances, the player's own prior moves and private messages, and no other player's private plans.

Step 4 adds `OutsmartGameRunner`. Its `runNextTurn(gameId)` method reads one game snapshot, collects every player's decision from that snapshot, and commits the completed turn once. A concurrent update is rejected by the registry's turn check. `runToCompletion(gameId)` repeats this until bankruptcy or turn 10; it blocks while local models respond, so a future HTTP endpoint should use background execution if it needs to return immediately. The game still has no HTTP endpoints or UI.
