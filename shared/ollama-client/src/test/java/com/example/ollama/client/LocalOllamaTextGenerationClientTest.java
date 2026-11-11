package com.example.ollama.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class LocalOllamaTextGenerationClientTest {

    @Test
    void sendsPromptAndModelToLocalOllamaEndpoint() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"id":"chatcmpl-test","object":"chat.completion","created":0,
                     "model":"test-model","choices":[{"index":0,"finish_reason":"stop",
                     "message":{"role":"assistant","content":"Hello back"}}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            var properties = new LocalOllamaProperties(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model");
            var client = new LocalOllamaTextGenerationClient(properties);

            assertEquals("Hello back", client.generateText("Hello"));
            assertEquals("/v1/chat/completions", path.get());
            assertTrue(body.get().contains("\"model\":\"test-model\""));
            ObjectMapper mapper = new ObjectMapper();
            JsonNode request = mapper.readTree(body.get());
            assertTrue(request.path("response_format").isMissingNode());
            JsonNode messages = request.path("messages");
            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).path("role").asString());
            assertEquals("Hello", messages.get(0).path("content").asString());

            assertEquals("Hello back", client.generateText("Be concise", "Hello"));
            request = mapper.readTree(body.get());
            assertTrue(request.path("response_format").isMissingNode());
            messages = request.path("messages");
            assertEquals(2, messages.size());
            assertEquals("system", messages.get(0).path("role").asString());
            assertEquals("Be concise", messages.get(0).path("content").asString());
            assertEquals("user", messages.get(1).path("role").asString());
            assertEquals("Hello", messages.get(1).path("content").asString());

            assertEquals("Hello back", client.generateText("Hello", TextResponseFormat.JSON_OBJECT));
            request = mapper.readTree(body.get());
            assertEquals("json_object", request.path("response_format").path("type").asString());
            messages = request.path("messages");
            assertEquals(2, messages.size());
            assertEquals("Respond with a valid JSON object.", messages.get(0).path("content").asString());

            assertEquals("Hello back", client.generateText("Be concise", "Hello", TextResponseFormat.JSON_OBJECT));
            request = mapper.readTree(body.get());
            assertEquals("json_object", request.path("response_format").path("type").asString());
            messages = request.path("messages");
            assertEquals("Be concise\nRespond with a valid JSON object.",
                    messages.get(0).path("content").asString());
        } finally {
            server.stop(0);
        }
    }
}
