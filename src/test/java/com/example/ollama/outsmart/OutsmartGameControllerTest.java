package com.example.ollama.outsmart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Checks the real Outsmart HTTP routes with scripted moves instead of a running Ollama model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutsmartGameControllerTest {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Exercises creation, public reads, one turn, deletion, and a missing-game response. */
    @Test
    void gameLifecycleUsesOnlyPublicResponseFields() throws IOException, InterruptedException {
        String request = """
                {"participants":[
                  {"name":"Alex","model":{"provider":"ollama","id":"gemma3:1b"}},
                  {"name":"Blake","model":{"provider":"ollama","id":"qwen3.5:4b"}},
                  {"name":"Charlie","model":{"provider":"ollama","id":"gemma3:1b"}}
                ]}
                """;
        HttpResponse<String> created = send("POST", "/api/outsmart/games", request);
        assertEquals(201, created.statusCode());
        JsonNode initial = mapper.readTree(created.body());
        assertEquals(0, initial.path("turnsCompleted").asInt());
        assertEquals("qwen3.5:4b", initial.path("players").get(1).path("modelId").asString());
        String gamePath = "/api/outsmart/games/" + initial.path("id").asString();
        assertEquals(gamePath, created.headers().firstValue("Location").orElseThrow());

        HttpResponse<String> played = send("POST", gamePath + "/turns", null);
        assertEquals(200, played.statusCode());
        JsonNode state = mapper.readTree(played.body());
        assertEquals(1, state.path("turnsCompleted").asInt());
        assertEquals(3, state.path("turns").get(0).path("coinsAfter").size());
        assertFalse(played.body().contains("private plan"));
        assertFalse(played.body().contains("private_messages"));
        assertFalse(played.body().contains("secretStrategy"));

        assertEquals(200, send("GET", gamePath, null).statusCode());
        assertEquals(204, send("DELETE", gamePath, null).statusCode());
        assertEquals(404, send("GET", gamePath, null).statusCode());
    }

    /** Rejects a provider that the Outsmart runner cannot call yet. */
    @Test
    void unsupportedProviderReturnsBadRequest() throws IOException, InterruptedException {
        String request = """
                {"participants":[
                  {"name":"Alex","model":{"provider":"openrouter","id":"some-model"}},
                  {"name":"Blake","model":{"provider":"ollama","id":"gemma3:1b"}},
                  {"name":"Charlie","model":{"provider":"ollama","id":"gemma3:1b"}}
                ]}
                """;

        HttpResponse<String> response = send("POST", "/api/outsmart/games", request);

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("provider must be ollama"));
    }

    /** Sends a request to the temporary Spring Boot port and returns the raw HTTP response. */
    private HttpResponse<String> send(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Supplies deterministic local moves so the HTTP test exercises orchestration without Ollama. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ScriptedMoves {

        /** Overrides the model call only inside this test application context. */
        @Bean
        @Primary
        OutsmartMoveGenerator scriptedMoveGenerator() {
            return (game, player) -> {
                String give = switch (player.name()) {
                    case "Alex" -> "Blake";
                    case "Blake" -> "Charlie";
                    default -> "Alex";
                };
                String take = switch (player.name()) {
                    case "Alex" -> "Charlie";
                    case "Blake" -> "Alex";
                    default -> "Blake";
                };
                return new OutsmartDecision.Valid(new OutsmartMove("private plan", give, take,
                        Map.of("Alex", "to Alex", "Blake", "to Blake", "Charlie", "to Charlie")));
            };
        }
    }
}
