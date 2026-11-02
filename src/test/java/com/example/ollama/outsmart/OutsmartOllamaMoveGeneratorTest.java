package com.example.ollama.outsmart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Verifies Outsmart prompts and Ollama response handling without a running model. */
class OutsmartOllamaMoveGeneratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    /** Releases the temporary local server after each HTTP test. */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Sends the participant's chosen model and parses a JSON move from Ollama's response shape. */
    @Test
    void sendsPlayerModelAndParsesMove() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        String moveJson = mapper.writeValueAsString(Map.of(
                "secret_strategy", "ally", "give_coin_to", "Blake", "take_coin_from", "Charlie",
                "private_messages", Map.of("Blake", "let's team up", "Charlie", "hello")));
        startServer(200, mapper.writeValueAsString(Map.of("message", Map.of("content", moveJson))), requestBody);
        OutsmartGame game = newGame();

        OutsmartDecision decision = client().decide(game, game.players().getFirst());

        OutsmartMove move = assertInstanceOf(OutsmartDecision.Valid.class, decision).move();
        assertEquals("ally", move.secretStrategy());
        assertEquals("Blake", move.giveCoinTo());
        assertEquals("Charlie", move.takeCoinFrom());
        JsonNode request = mapper.readTree(requestBody.get());
        assertEquals("local-test-model", request.path("model").asString());
        assertEquals("json", request.path("format").asString());
        assertFalse(request.path("stream").booleanValue());
        assertEquals("system", request.path("messages").get(0).path("role").asString());
        assertEquals("user", request.path("messages").get(1).path("role").asString());
    }

    /** Turns malformed model output into a forfeited decision instead of stopping the game. */
    @Test
    void malformedJsonBecomesInvalidDecision() throws IOException {
        startServer(200, "{\"message\":{\"content\":\"not json\"}}", new AtomicReference<>());
        OutsmartGame game = newGame();

        OutsmartDecision decision = client().decide(game, game.players().getFirst());

        assertInstanceOf(OutsmartDecision.Invalid.class, decision);
    }

    /** Keeps transport failures as invalid moves so the rule engine can finish the turn. */
    @Test
    void serverErrorBecomesInvalidDecision() throws IOException {
        startServer(503, "unavailable", new AtomicReference<>());
        OutsmartGame game = newGame();

        OutsmartDecision.Invalid decision = assertInstanceOf(OutsmartDecision.Invalid.class,
                client().decide(game, game.players().getFirst()));

        assertTrue(decision.reason().contains("503"));
    }

    /** Shows a player their own plan and received messages, but no opponent's private plan. */
    @Test
    void promptContainsOnlyPlayersOwnPrivateHistory() {
        OutsmartGame game = newGame();
        game = new OutsmartRuleEngine().playTurn(game, Map.of(
                "Alex", move("Alex", "Blake", "Charlie", "alex private plan"),
                "Blake", move("Blake", "Charlie", "Alex", "blake private plan"),
                "Charlie", move("Charlie", "Alex", "Blake", "charlie private plan")));

        String prompt = new OutsmartPromptFactory().forPlayer(game, game.players().getFirst()).user();

        assertTrue(prompt.contains("alex private plan"));
        assertTrue(prompt.contains("Blake to Alex"));
        assertFalse(prompt.contains("blake private plan"));
        assertFalse(prompt.contains("charlie private plan"));
        assertFalse(prompt.contains("Blake to Charlie"));
    }

    /** Creates a three-player game with a distinct model ID chosen for its first participant. */
    private OutsmartGame newGame() {
        return OutsmartGame.start(List.of(
                new OutsmartGame.Participant("Alex", new OutsmartGame.Model("ollama", "local-test-model")),
                new OutsmartGame.Participant("Blake", new OutsmartGame.Model("ollama", "other-model")),
                new OutsmartGame.Participant("Charlie", new OutsmartGame.Model("ollama", "third-model"))));
    }

    /** Builds a fully populated decision for the privacy test. */
    private OutsmartDecision.Valid move(String sender, String give, String take, String strategy) {
        return new OutsmartDecision.Valid(new OutsmartMove(strategy, give, take,
                Map.of("Alex", sender + " to Alex", "Blake", sender + " to Blake",
                        "Charlie", sender + " to Charlie")));
    }

    /** Creates the adapter with this test's temporary Ollama endpoint. */
    private OutsmartOllamaMoveGenerator client() {
        URI base = URI.create("http://localhost:" + server.getAddress().getPort());
        OutsmartProperties settings = new OutsmartProperties(10,
                new OutsmartProperties.Ollama(base, Duration.ofSeconds(5)));
        return new OutsmartOllamaMoveGenerator(HttpClient.newHttpClient(), mapper,
                new OutsmartPromptFactory(), settings);
    }

    /** Returns a fixed Ollama-like response and captures the submitted request body. */
    private void startServer(int status, String response, AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.start();
    }
}
