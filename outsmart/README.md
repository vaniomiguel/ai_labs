# Outsmart game

A standalone Java 25 / Spring Boot application with a Thymeleaf and HTMX interface.

## Run

From the repository root, with Ollama running and the player models installed:

```sh
./mvnw -pl outsmart -am package
java -jar outsmart/target/outsmart-0.0.1-SNAPSHOT.jar
```

Open [http://localhost:8082/outsmart](http://localhost:8082/outsmart). `PORT` overrides the default port.

## Behavior and API


The Outsmart feature has its own `com.example.ollama.outsmart` package and `outsmart.*` configuration. Its OpenAI SDK client connects to local Ollama through `OUTSMART_OLLAMA_BASE_URL` (default `http://localhost:11434`) and `OUTSMART_OLLAMA_REQUEST_TIMEOUT` (default `2m`). These settings do not affect `/api/chat`.

The game rules and in-memory game registry are implemented. Games start with 12 coins per player and end after 10 turns or a bankruptcy. Invalid moves cost one coin; a qualifying alliance transfers two extra coins from its target. The registry keeps up to `OUTSMART_MAX_GAMES` games (default `100`) in memory and supports removing them; games disappear when the application stops.

Player-specific prompts and an Ollama move generator use the model ID supplied for each participant. The generator uses the OpenAI SDK's chat completions and JSON schema response format. Malformed model output becomes an invalid move; an unavailable Ollama server stops the turn without changing coins. The rule engine checks target names and required messages. Each prompt includes public balances, the player's own prior moves and private messages, and no other player's private plans.

`OutsmartGameRunner.runNextTurn(gameId)` reads one game snapshot, collects every player's decision from that snapshot, and commits the completed turn once. `OutsmartGameExecutionService` prevents overlapping turns and runs whole games in a background virtual thread. A concurrent update is also rejected by the registry's turn check.

Step 5 adds the Outsmart API. Create a game with 3–8 players and an installed Ollama model for each player:

```bash
curl -i -X POST http://localhost:8082/api/outsmart/games \
  -H 'Content-Type: application/json' \
  -d '{"participants":[{"name":"Alex","model":{"provider":"ollama","id":"gemma3:1b"}},{"name":"Blake","model":{"provider":"ollama","id":"gemma3:1b"}},{"name":"Charlie","model":{"provider":"ollama","id":"gemma3:1b"}}]}'
```

The response includes an `id` and a `Location` header. Use that ID for the other operations:

```bash
curl http://localhost:8082/api/outsmart/games/GAME_ID
curl -X POST http://localhost:8082/api/outsmart/games/GAME_ID/turns
curl -X POST http://localhost:8082/api/outsmart/games/GAME_ID/run
curl -X DELETE http://localhost:8082/api/outsmart/games/GAME_ID
```

Each `POST .../turns` runs one turn and waits for every local model response. `POST .../run` starts whole-game play in the background and normally returns `202`; poll `GET .../games/GAME_ID` for `run.state` and `turnsCompleted`. Repeated Run Game calls do not start duplicate work. The JSON API exposes balances and turn history, but no private strategies or messages. Invalid requests return `400`, unknown game IDs return `404`, conflicting turns return `409`, and an unavailable Ollama model returns `503`. Games remain only in memory and disappear on restart.

The browser interface is at [http://localhost:8082/outsmart](http://localhost:8082/outsmart). With Ollama running and models pulled, start the app using the commands above. Enter names for at least three players and select or type model IDs; the fields suggest models returned by the OpenAI-compatible model list through the SDK. Use **Play next turn** for one turn or **Run game** to continue in the background. The page polls while a whole game runs and shows balances, messages, alliances, and optional inner thoughts after each turn. These details are a human spectator view; they are never included in another model's prompt. **Delete game** frees its in-memory slot. Standard form submissions also work without JavaScript.

The browser interface uses server-rendered Thymeleaf and a locally bundled copy of HTMX 2.0.11 (`outsmart/src/main/resources/static/vendor/htmx.min.js`); its license is stored beside it. It has no separate frontend build step and needs no CDN connection at runtime.

