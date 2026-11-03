# AI Labs

AI Labs is a growing collection of projects for exploring artificial intelligence. It brings together experiments, prototypes, and small applications built to test concepts, try ideas, and learn by doing.

This repository is a work in progress. Projects and documentation will be added and changed over time as new ideas are explored.

## Projects

### Spring Boot chat with local Ollama

A Java 25 / Spring Boot REST API that uses a local Ollama model. `ChatService` depends on `TextGenerationClient`; `LocalOllamaTextGenerationClient` is the current implementation. The interface supports a user prompt alone or a system prompt plus a user prompt. Both forms accept an optional `TextResponseFormat`: `DEFAULT` leaves the provider's response format unchanged, while `JSON_OBJECT` requests JSON object output. A future server or cloud provider can supply another implementation without changing the chat endpoint or service.

#### Requirements

- Java 25 JDK
- The included Maven wrapper (Maven is downloaded on first use)
- [Ollama](https://ollama.com/) installed and running locally, with a model downloaded (default: `gemma3:1b`)

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

Choose another installed Ollama model with `OLLAMA_MODEL`; set `OLLAMA_BASE_URL` if your local Ollama server uses another address:

```bash
OLLAMA_MODEL=qwen3.5:4b OLLAMA_BASE_URL=http://localhost:11434/v1 ./mvnw spring-boot:run
```

The local adapter uses Ollama's OpenAI-compatible API. Its placeholder API key is internal; Ollama does not require credentials. The Outsmart game has a separate move-generator interface and its own Ollama settings.

### Outsmart game (in progress)

The Outsmart feature has its own `com.example.ollama.outsmart` package and `outsmart.*` configuration. Its OpenAI SDK client connects to local Ollama through `OUTSMART_OLLAMA_BASE_URL` (default `http://localhost:11434`) and `OUTSMART_OLLAMA_REQUEST_TIMEOUT` (default `2m`). These settings do not affect `/api/chat`.

The `mise.toml` file selects Temurin 25 for this project. Link the Homebrew installation with `mise link java@temurin-25 /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`, then run `./mvnw` from a shell with mise activated.

The game rules and in-memory game registry are implemented. Games start with 12 coins per player and end after 10 turns or a bankruptcy. Invalid moves cost one coin; a qualifying alliance transfers two extra coins from its target. The registry keeps up to `OUTSMART_MAX_GAMES` games (default `100`) in memory and supports removing them; games disappear when the application stops.

Player-specific prompts and an Ollama move generator use the model ID supplied for each participant. The generator uses the OpenAI SDK's chat completions and JSON schema response format. Malformed model output becomes an invalid move; an unavailable Ollama server stops the turn without changing coins. The rule engine checks target names and required messages. Each prompt includes public balances, the player's own prior moves and private messages, and no other player's private plans.

`OutsmartGameRunner.runNextTurn(gameId)` reads one game snapshot, collects every player's decision from that snapshot, and commits the completed turn once. `OutsmartGameExecutionService` prevents overlapping turns and runs whole games in a background virtual thread. A concurrent update is also rejected by the registry's turn check.

Step 5 adds the Outsmart API. Create a game with 3–8 players and an installed Ollama model for each player:

```bash
curl -i -X POST http://localhost:8080/api/outsmart/games \
  -H 'Content-Type: application/json' \
  -d '{"participants":[{"name":"Alex","model":{"provider":"ollama","id":"gemma3:1b"}},{"name":"Blake","model":{"provider":"ollama","id":"gemma3:1b"}},{"name":"Charlie","model":{"provider":"ollama","id":"gemma3:1b"}}]}'
```

The response includes an `id` and a `Location` header. Use that ID for the other operations:

```bash
curl http://localhost:8080/api/outsmart/games/GAME_ID
curl -X POST http://localhost:8080/api/outsmart/games/GAME_ID/turns
curl -X POST http://localhost:8080/api/outsmart/games/GAME_ID/run
curl -X DELETE http://localhost:8080/api/outsmart/games/GAME_ID
```

Each `POST .../turns` runs one turn and waits for every local model response. `POST .../run` starts whole-game play in the background and normally returns `202`; poll `GET .../games/GAME_ID` for `run.state` and `turnsCompleted`. Repeated Run Game calls do not start duplicate work. The JSON API exposes balances and turn history, but no private strategies or messages. Invalid requests return `400`, unknown game IDs return `404`, conflicting turns return `409`, and an unavailable Ollama model returns `503`. Games remain only in memory and disappear on restart.

The browser interface is at [http://localhost:8080/outsmart](http://localhost:8080/outsmart). With Ollama running and models pulled, start the app with `./mvnw spring-boot:run`. Enter names for at least three players and select or type model IDs; the fields suggest models returned by the OpenAI-compatible model list through the SDK. Use **Play next turn** for one turn or **Run game** to continue in the background. The page polls while a whole game runs and shows balances, messages, alliances, and optional inner thoughts after each turn. These details are a human spectator view; they are never included in another model's prompt. **Delete game** frees its in-memory slot. Standard form submissions also work without JavaScript.

The browser interface uses server-rendered Thymeleaf and a locally bundled copy of HTMX 2.0.11 (`src/main/resources/static/vendor/htmx.min.js`); its license is stored beside it. It has no separate frontend build step and needs no CDN connection at runtime.

### Company brochure

The brochure API reads a website, selects relevant linked pages with the configured model, and returns a Markdown brochure:

```bash
curl --get http://localhost:8080/api/brochures \
  --data-urlencode 'url=https://example.com'
```

The response uses the `text/markdown` media type. A browser page needs a Markdown renderer to display its headings and paragraphs with formatting.
